# JieLi OTA — SDK internals & this library's design

This document is the "everything I learned" deep dive: how the JieLi (杰理 / JL)
`jl_bt_ota` Android SDK works, why this React Native library is shaped the way it
is, and the details you need when something goes wrong.

Sources studied:

- Official repo & demo app: <https://gitee.com/Jieli-Tech/Android-JL_OTA>
- SDK AAR: `jl_bt_ota_V1.11.0_11015-release.aar` (SDK version 1.11.0, build 11015)
- JL framework docs: <https://doc.zh-jieli.com/Apps/Android/ota/en-us/master/>
- A second, independently built integration (`expo-jl-ota`) that owns BLE natively
  end to end and is proven reliable in production — this library's native BLE
  engine (`JlOtaEngine`, `ble/BleManager`, `ble/ReConnectHelper`, etc.) is a
  faithful Kotlin port of that integration's Java code.

---

## 1. The big picture: RCSP + an abstract transport

JieLi devices speak **RCSP** (a JieLi command/response protocol) over a transport
that can be **BLE**, **SPP** (classic Bluetooth), or GATT-over-BR/EDR. OTA is one
feature built on top of RCSP.

The SDK's entry point is the abstract class:

```
com.jieli.jl_bt_ota.impl.BluetoothOTAManager
    implements IBluetoothManager, IUpgradeManager
```

The key architectural fact: **`BluetoothOTAManager` does not own the Bluetooth
connection.** It implements the protocol and asks *you* to move bytes. You must
implement five transport methods (`IBluetoothManager`):

```java
BluetoothDevice getConnectedDevice();
BluetoothGatt   getConnectedBluetoothGatt();
void            connectBluetoothDevice(BluetoothDevice device);
void            disconnectBluetoothDevice(BluetoothDevice device);
boolean         sendDataToDevice(BluetoothDevice device, byte[] data);
```

…and you feed inbound events *back into* the SDK:

```java
void onReceiveDeviceData(BluetoothDevice device, byte[] data); // a notification arrived
void onBtDeviceConnection(BluetoothDevice device, int state);  // link up/down
void onMtuChanged(BluetoothGatt gatt, int mtu, int status);    // MTU negotiated
void onError(BaseError error);
```

**This library owns BLE natively** and implements all five methods against a real
`android.bluetooth.BluetoothGatt` connection it manages itself (`JlOtaEngine` +
`ble/BleManager`) — there is no JS-side GATT involvement. This is the same
integration shape as the JieLi reference demo app, not the "external transport"
path — the difference from the demo is packaging (an Expo/RN native module) and a
few naming/API choices, not the underlying BLE logic, which is a direct, faithful
port.

> **History note:** an earlier version of this library instead delegated the five
> transport methods to JavaScript, so a host app's existing
> `react-native-ble-plx` connection could act as the transport (avoiding a second
> GATT connection to the same device). That design is no longer used — the native
> BLE engine here is a proven, from-scratch Bluetooth stack (scan, connect,
> service discovery, notification enable, MTU negotiation, write queue, dual-bank
> reconnect) that does not require or interact with any BLE connection the host
> app might separately hold.

---

## 2. The native BLE engine, concretely

Three cooperating pieces, all under `android/src/main/java/com/astrivix/reactjlota/`:

| Class | Role |
|---|---|
| `JlOtaEngine` (extends `BluetoothOTAManager`) | Implements the 5 transport methods against `BleManager`; bridges `BleManager`'s connection/notification/MTU events into the SDK's `onBtDeviceConnection`/`onReceiveDeviceData`/`onMtuChanged`. |
| `ble/BleManager` | Owns the actual `BluetoothGatt`: LE scan (filtering for the target MAC), `connectGatt`, service discovery, enabling the AE02 notification descriptor, MTU negotiation, and a serialized write queue (`SendBleDataThread`) with retry. |
| `ble/ReConnectHelper` | Dual-bank reconnect: on `onNeedReconnect`, scans for the (possibly new) address and reconnects, entirely in native code. |

`ReactJlOtaModule` creates a `JlOtaEngine` per target `deviceAddress` (recreating it
if `startOta` is called with a different address — mirrors the reference's
"recreate manager when the mac changes" behavior), waits for the engine's own
`BtEventCallback.onConnection(status = CONNECTION_OK)` before calling
`startOTA(IUpgradeCallback)`, and forwards `onNeedReconnect` straight into
`JlOtaEngine.reConnect(address, isNewWay)`.

Because `getConnectedBluetoothGatt()` now returns a **real** GATT (unlike the old
JS-bridge design, which returned `null` to disable this), MTU renegotiation is
live: `BleManager` requests it unconditionally right after enabling notifications,
independent of `BluetoothOTAConfigure.isNeedChangeMtu` — which this library still
sets to `false` so the SDK itself doesn't *also* attempt `gatt.requestMtu(...)` and
race with `BleManager`'s own negotiation.

---

## 3. Configuration (`BluetoothOTAConfigure`)

Built from the proven reference's own config. Fields this lib sets / exposes:

| Field | Default here | Meaning |
|---|---|---|
| `priority` | `PREFER_BLE` | BLE vs SPP transport (`useSpp` flips it) |
| `isNeedChangeMtu` | `false` | `BleManager` already renegotiates MTU itself; avoids a redundant/racing SDK-driven attempt |
| `mtu` | `500` | requested MTU; real negotiated value comes back through `BleManager`'s `onMtuChanged` path |
| `isUseReconnect` | `true` | SDK reconnect awareness — native reconnect via `ReConnectHelper` always runs on `onNeedReconnect` regardless |
| `isUseAuthDevice` | `true` | device authentication handshake — **ask your firmware engineer** if OTA fails at the auth step |
| `bleIntervalMs` | `500` | connection-interval hint |
| `timeoutMs` | `3000` | command timeout |

`isUseAuthDevice` matters: if the firmware requires authentication and you set it
`false`, OTA fails early with `SUB_ERR_AUTH_DEVICE`; if it does **not** require auth
and you set `true`, it also fails. This is a firmware-side decision — confirm it.

---

## 4. The OTA lifecycle (`IUpgradeCallback`)

`startOTA(IUpgradeCallback)` drives these callbacks, which this lib maps to events
and the `startOta` promise:

| SDK callback | Event emitted | Promise |
|---|---|---|
| `onStartOTA()` | `onOtaStateChange{state:'start'}` | — |
| `onProgress(type, %)` | `onOtaProgress{type, progress}` | — |
| `onNeedReconnect(addr, newWay)` | `onOtaNeedReconnect` + `state:'reconnect'` | native reconnect kicked off via `JlOtaEngine.reConnect` |
| `onStopOTA()` | `state:'stop'` | **resolve** |
| `onCancelOTA()` | `state:'cancel'` | **reject** `ERR_OTA_CANCELLED` |
| `onError(BaseError)` | `onOtaError{code,subCode,message}` | **reject** `ERR_OTA_FAILED` |

`type` in `onProgress` is a firmware-defined phase indicator (e.g. erase vs write vs
verify); treat the `progress` float (0–100) as the user-facing number.

### `onNeedReconnect` — dual-bank / address change

Many JieLi OTA designs are **dual-bank**: the device writes the new firmware to a
spare bank, reboots into a loader, and **re-advertises with a changed MAC** (often
MAC + 1). The SDK signals this via `onNeedReconnect`, and `ReConnectHelper` (ported
from the proven reference) handles it entirely natively: it scans for the new
address (or the same address if not using a new-ADV scheme), reconnects, and lets
the SDK resume once the link is back up — no JS action required.

Single-bank devices never fire this.

---

## 5. GATT profile & framing

From `BluetoothConstant` in the AAR:

```
UUID_SERVICE      = 0000ae00-0000-1000-8000-00805F9B34FB
UUID_WRITE        = 0000ae01-0000-1000-8000-00805F9B34FB
UUID_NOTIFICATION = 0000ae02-0000-1000-8000-00805F9B34FB   (notify)
CCCD              = 00002902-0000-1000-8000-00805F9B34FB
BLE_MTU_MIN = 20,  BLE_MTU_MAX = 509
```

`BleManager` subscribes to AE02, writes SDK output to AE01, and enables the CCCD
descriptor itself — none of this is exposed to JS anymore, since there's no JS-side
GATT to wire it into.

RCSP packets are length-prefixed and reassembled inside the SDK; `BleManager`
forwards raw notification payloads as-is.

---

## 6. Error codes (`ErrorCode` sub-codes you'll actually see)

| Sub-code | Constant | Meaning |
|---|---|---|
| 4114 | `SUB_ERR_REMOTE_NOT_CONNECTED` | link not up when OTA started |
| 12290 | `SUB_ERR_SEND_FAILED` | a write failed |
| 12295 | `SUB_ERR_SEND_TIMEOUT` | device didn't ack a packet |
| 12299 | `SUB_ERR_WAITING_COMMAND_TIMEOUT` | no reply to a command |
| 16385 | `SUB_ERR_OTA_FAILED` | generic OTA failure |
| 16386 | `SUB_ERR_DEVICE_LOW_VOLTAGE` | battery too low to upgrade |
| 16387 | `SUB_ERR_CHECK_UPGRADE_FILE` | bad / corrupt firmware file |
| 16390 | `SUB_ERR_UPGRADE_KEY_NOT_MATCH` | firmware signed for a different key |
| 16391 | `SUB_ERR_UPGRADE_TYPE_NOT_MATCH` | wrong firmware type for this chip |
| 16396 | `SUB_ERR_UPGRADE_FILE_VERSION_SAME` | already on this version |
| 16401/16402 | `SUB_ERR_RECONNECT_TIMEOUT/FAILED` | dual-bank reconnect failed |
| 20481 | `SUB_ERR_AUTH_DEVICE` | auth mismatch (see `useAuthDevice`) |

`onOtaError.message` carries the SDK's human-readable string; `subCode` is the
stable thing to branch on.

`StateCode` connection values: `0` disconnect, `1` ok, `2` failed, `3` connecting,
`4` connected.

---

## 7. Packaging the AAR

The vendor AAR lives in `android/libs/` and is pulled in via:

```gradle
implementation fileTree(dir: 'libs', include: ['*.aar'])
```

The AAR is **self-contained** — its only external references are
`androidx.annotation` and `androidx.core` (both already present in any RN app). It
also bundles native `libjl_ota_auth.so` for `armeabi-v7a`, `arm64-v8a`, `x86`,
`x86_64`; the module restricts `ndk.abiFilters` to those.

### "Direct local .aar file dependencies are not supported"

This AGP error only fires when a `com.android.library` is **assembled into a
standalone published AAR** (`:assembleRelease`/`:bundleReleaseAar`). Expo/RN consume
this module as a **project dependency**, where AGP uses the exploded classes and the
local AAR resolves fine. If you ever hit the error (e.g. a strict AGP setup or trying
to publish the module as a binary), the workaround is to extract the AAR's
`classes.jar` + `jni/` and ship them directly, or host the AAR in a Maven repo and
depend on it by coordinate.

---

## 8. iOS

There is **no JieLi OTA SDK for iOS** in this package — `jl_bt_ota` is Android-only.
The iOS module is a stub: it exposes the same JS surface but rejects OTA calls with
`ERR_UNSUPPORTED_PLATFORM`. Supporting iOS would require JieLi's iOS OTA SDK and a
parallel `BluetoothOTAManager`-equivalent native BLE engine.

---

## 9. Verifying a build

```bash
# from the example app, after `npx expo prebuild`:
cd example/android && ./gradlew :react-jl-ota:compileDebugKotlin
```

A full end-to-end test needs a real JieLi device and a valid `.ufw` file. Grant the
runtime BLE permissions (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, plus location on
Android < 12) in the host app before calling `startOta` — the native scan silently
no-ops without them (see `tool/AppUtil.checkHasScanPermission` /
`isHasLocationPermission`).
