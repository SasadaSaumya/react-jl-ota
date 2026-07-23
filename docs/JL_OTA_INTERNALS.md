# JieLi OTA — SDK internals & this library's design

This document is the "everything I learned" deep dive: how the JieLi (杰理 / JL)
`jl_bt_ota` Android SDK works, why this React Native library is shaped the way it
is, and the details you need when something goes wrong.

Sources studied:

- Official repo & demo app: <https://gitee.com/Jieli-Tech/Android-JL_OTA>
- SDK AAR: `jl_bt_ota_V1.11.0_11015-release.aar` (SDK version 1.11.0, build 11015)
- JL framework docs: <https://doc.zh-jieli.com/Apps/Android/ota/en-us/master/>
- The demo's reference integration class `OTAManager.kt` + `OTAViewModel.kt`

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

This is exactly the **"external library / 外接库"** integration path in the JL docs.
It is *the* reason this React Native library can exist without re-implementing BLE:
we let your existing `react-native-ble-plx` connection be the transport.

### Two ways to integrate (and why we chose one)

| | Native-owned BLE | **JS-bridge transport (this lib)** |
|---|---|---|
| Who scans/connects | the SDK's `BleManager` | your `react-native-ble-plx` code |
| GATT connections | a 2nd one (conflicts) | reuse the one you already have |
| Matches JL demo | yes (copy `BleManager`) | uses the documented external-lib path |
| App changes | hand off the device | wire 2 callbacks (write + notify) |

Android allows **one GATT connection per device**. An app that already controls the
device over `react-native-ble-plx` cannot have the SDK open a second one cleanly.
So we bridge the transport to JS.

---

## 2. The bridge, concretely

`JlOtaBridgeManager.kt` subclasses `BluetoothOTAManager` and maps the five transport
methods onto JS:

| SDK call | What we do |
|---|---|
| `getConnectedDevice()` | return a `BluetoothDevice` resolved from the MAC JS passed to `startOta` |
| `getConnectedBluetoothGatt()` | **return `null`** — JS owns the GATT; MTU changes are disabled |
| `connectBluetoothDevice()` | emit `onOtaConnectRequest` (JS reconnects) |
| `disconnectBluetoothDevice()` | emit `onOtaDisconnectRequest` |
| `sendDataToDevice(d, bytes)` | emit `onOtaWriteRequest{dataBase64}`, **block (≤8s) for the real write outcome** |

Inbound:

| JS call | SDK call |
|---|---|
| `notifyData(base64)` | `onReceiveDeviceData(activeDevice, bytes)` |
| `notifyWriteResult(success)` | unblocks the in-flight `sendDataToDevice` call, returning `success` |
| `notifyConnectionState(bool)` | `onBtDeviceConnection(activeDevice, CONNECTION_OK/DISCONNECT)` |
| `setActiveDevice(address)` | `activeDevice = BluetoothAdapter.getRemoteDevice(address)` |

Because `getConnectedBluetoothGatt()` is `null`, we **must** disable the SDK's MTU
renegotiation (`setNeedChangeMtu(false)`) — otherwise it would try to call
`gatt.requestMtu(...)` on a null GATT. The per-packet payload size is therefore
fixed by `BluetoothOTAConfigure.mtu` (default 20, the BLE minimum), and you raise it
explicitly after negotiating a larger MTU in `react-native-ble-plx`.

### Why `sendDataToDevice` blocks for the real write result

Earlier versions of this library returned `true` from `sendDataToDevice`
optimistically, the instant the write request was dispatched to JS, on the
assumption that the SDK thread couldn't be blocked waiting for a JS round-trip.
That assumption was never actually verified, and it has a real cost: the SDK
starts its own "waiting for device reply" timeout the moment `sendDataToDevice`
returns — so with an optimistic ack, that clock started *before* the bytes had
even reached the JS bridge, let alone gone out over the air. On a real device
this manifested as the very first command (`GetTargetInfoCmd`) reliably timing
out (`SUB_ERR_SEND_TIMEOUT` 12295 / `SUB_ERR_WAITING_COMMAND_TIMEOUT` 12299)
even with a generously bumped per-command timeout.

`sendDataToDevice` now blocks (bounded to `WRITE_ACK_TIMEOUT_MS` = 8000ms,
matching the JieLi reference Android app's own `SEND_DATA_MAX_TIMEOUT`) until
JS calls `notifyWriteResult(success)` after the real
`writeCharacteristicWithResponseForService` promise settles. This is safe from
deadlock: `notifyWriteResult` is always invoked from the RN bridge thread,
driven by ble-plx's native GATT callback — a different thread than whatever the
SDK calls `sendDataToDevice` from — so nothing the blocked thread itself needs
to do is required to unblock it. If a write's ack genuinely never arrives (JS
never settles), the bound simply times out and the call reports failure, same
as any other failed write the SDK already knows how to handle.

**You must call `notifyWriteResult` from your `onWriteRequest` handler** for
every write, on both success and failure — otherwise every write times out
after `WRITE_ACK_TIMEOUT_MS` regardless of what actually happened on the wire.

---

## 3. Configuration (`BluetoothOTAConfigure`)

Built from the demo's reference config. Fields this lib sets / exposes:

| Field | Default here | Meaning |
|---|---|---|
| `priority` | `PREFER_BLE` | BLE vs SPP transport (`useSpp` flips it) |
| `isNeedChangeMtu` | `false` | **must stay false** (null GATT) |
| `mtu` | `20` | bytes per write packet (`MTU_MIN`=20 … `MTU_MAX`=509) |
| `isUseReconnect` | `false` | SDK-managed reconnect; we let JS do it |
| `isUseAuthDevice` | `false` | device authentication handshake — **ask your firmware engineer** |
| `isUseJLServer` | `false` | JieLi cloud features (unused) |
| `bleScanMode` | `LOW_LATENCY` | only relevant if SDK scans (it doesn't here) |
| `timeoutMs`, `bleIntervalMs` | SDK default | tunables |

`isUseAuthDevice` matters: if the firmware requires authentication and you leave it
`false`, OTA fails early; if it does **not** and you set `true`, it also fails. This
is a firmware-side decision — confirm it.

---

## 4. The OTA lifecycle (`IUpgradeCallback`)

`startOTA(IUpgradeCallback)` drives these callbacks, which this lib maps to events
and the `startOta` promise:

| SDK callback | Event emitted | Promise |
|---|---|---|
| `onStartOTA()` | `onOtaStateChange{state:'start'}` | — |
| `onProgress(type, %)` | `onOtaProgress{type, progress}` | — |
| `onNeedReconnect(addr, newWay)` | `onOtaNeedReconnect` + `state:'reconnect'` | — |
| `onStopOTA()` | `state:'stop'` | **resolve** |
| `onCancelOTA()` | `state:'cancel'` | **reject** `ERR_OTA_CANCELLED` |
| `onError(BaseError)` | `onOtaError{code,subCode,message}` | **reject** `ERR_OTA_FAILED` |

`type` in `onProgress` is a firmware-defined phase indicator (e.g. erase vs write vs
verify); treat the `progress` float (0–100) as the user-facing number.

### `onNeedReconnect` — dual-bank / address change

Many JieLi OTA designs are **dual-bank**: the device writes the new firmware to a
spare bank, reboots into a loader, and **re-advertises with MAC address + 1** (the
demo literally does `addr[last] + 1`). The SDK signals this via `onNeedReconnect`.
With the JS-bridge model, your app must:

1. connect to `reconnectAddress`,
2. re-subscribe AE02,
3. call `setActiveDevice(reconnectAddress)` — **required whenever the address
   changed**, otherwise `JlOtaBridgeManager.activeDevice` (and therefore
   `getConnectedDevice()`) keeps returning the stale pre-reboot
   `BluetoothDevice` even though your JS transport moved to the new one,
4. call `notifyConnectionState(true)`.

Single-bank devices never fire this. If your OTA consistently dies at the very end,
this is almost always the cause.

⚠️ Do not tear down the AE02 monitor on the *old* device by calling
`.remove()` on it before/after reconnecting — see the crash warning in the
README's Quick start. Let it dangle behind a `disposed`/epoch guard instead.

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

- Subscribe to **AE02** and forward every packet to `notifyData`.
- Write SDK output to **AE01** **with response**. AE01 supports write-without-response
  as a GATT property, but the JieLi reference Android app never opts into it
  (`BleDevice.writeDataToDeviceByBle` never calls `setWriteType(WRITE_TYPE_NO_RESPONSE)`,
  so it always uses Android's with-response default). Writing without response has
  been observed to silently stall on a real device — the very first `GetTargetInfoCmd`
  got zero replies and eventually failed with `SUB_ERR_WAITING_COMMAND_TIMEOUT` (12295),
  with the local write reporting success the whole time. Use
  `writeCharacteristicWithResponseForService` in ble-plx.
- `react-native-ble-plx` exchanges characteristic values as **base64**, which lines
  up exactly with this lib's `dataBase64` in/out — no manual hex conversion needed.

RCSP packets are length-prefixed and reassembled inside the SDK, so you forward raw
notification payloads as-is; do not try to parse or merge them yourself.

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
parallel `BluetoothOTAManager`-equivalent bridge.

---

## 9. Verifying a build

```bash
# from the example app, after `npx expo prebuild`:
cd example/android && ./gradlew :react-jl-ota:compileDebugKotlin
```

A full end-to-end test needs a real JieLi device and a valid `.ufw` file. The
`react-native-ble-plx` connection must be live, AE02 subscribed, and the
`onOtaWriteRequest` handler wired before calling `startOta`.
```
