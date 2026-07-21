# react-jl-ota

React Native / Expo OTA firmware-update library for **JieLi (杰理 / JL)** Bluetooth
chips, wrapping the official `jl_bt_ota` Android SDK.

It is built for apps that **already manage their own BLE connection with
[`react-native-ble-plx`](https://github.com/dotintent/react-native-ble-plx)**.
The native module runs only the JieLi OTA (RCSP) protocol; **your JS keeps owning
the BLE link**. No second GATT connection, no duplicate scanning, no conflict with
your existing device-controller app.

> Platform support: **Android only.** The JieLi OTA SDK is an Android `.aar`.
> The library is importable on iOS/web but every OTA call rejects with
> `ERR_UNSUPPORTED_PLATFORM`.

---

## How it works

The JieLi `BluetoothOTAManager` is an *abstract transport* — it implements the OTA
protocol but delegates the actual reading/writing of bytes. This library plugs that
transport into your `react-native-ble-plx` connection:

```
 JS (your app, react-native-ble-plx)          Native (react-jl-ota + JL SDK)
 ────────────────────────────────────         ──────────────────────────────
 scan + connect the device          ─────────▶  (already connected)
 subscribe to AE02 notify char
 onOtaWriteRequest  ◀───────────────────────── SDK wants to send bytes
   → write bytes to AE01
 AE02 notification ──── notifyData() ────────▶  SDK parses device reply
 startOta({ deviceAddress, … })     ─────────▶  BluetoothOTAManager.startOTA()
 onOtaProgress / resolve(startOta)  ◀───────── IUpgradeCallback
```

**JieLi OTA GATT profile** (exported as `JL_OTA_UUIDS`):

| Role        | UUID                                   |
|-------------|----------------------------------------|
| Service     | `0000ae00-0000-1000-8000-00805f9b34fb` |
| Write (AE01)| `0000ae01-0000-1000-8000-00805f9b34fb` |
| Notify(AE02)| `0000ae02-0000-1000-8000-00805f9b34fb` |
| CCCD        | `00002902-0000-1000-8000-00805f9b34fb` |

For a deeper explanation of the SDK internals, the RCSP protocol, dual-bank
reconnection and error codes, see [`docs/JL_OTA_INTERNALS.md`](docs/JL_OTA_INTERNALS.md).

---

## Installation

This is a local Expo module that bundles the vendor AAR. The AAR is already in
`android/libs/jl_bt_ota_V1.11.0_11015-release.aar`.

In your app:

```bash
npm install react-jl-ota react-native-ble-plx
npx expo prebuild   # config plugin / autolinking picks up the native module
```

`react-native-ble-plx` requires a custom dev client (it does not run in Expo Go).
Add its config plugin in `app.json` and request the BLE runtime permissions
(`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and location on Android < 12).

> **Min Android SDK 21.** The vendor AAR ships native `.so` files for
> `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` only.

---

## Quick start

```ts
import { Buffer } from 'buffer';
import { BleManager, Device } from 'react-native-ble-plx';
import * as JlOta from 'react-jl-ota';

const ble = new BleManager();

/**
 * Run an OTA on an already-connected ble-plx Device.
 * @param device  a connected react-native-ble-plx Device
 * @param filePath absolute path to the .ufw firmware file on disk
 */
export async function runOta(device: Device, filePath: string) {
  await device.discoverAllServicesAndCharacteristics();

  // Guards so late/duplicate callbacks are no-ops instead of touching a torn-down
  // monitor — see the ⚠️ note below on why we never call notifySub.remove().
  let disposed = false;

  // 1) Forward every AE02 notification into the OTA engine.
  const notifySub = device.monitorCharacteristicForService(
    JlOta.JL_OTA_UUIDS.service,
    JlOta.JL_OTA_UUIDS.notify,
    (error, characteristic) => {
      if (disposed) return;
      if (error || !characteristic?.value) return;
      JlOta.notifyData(characteristic.value); // ble-plx value is already base64
    }
  );

  // 2) When the SDK wants to send bytes, write them to AE01.
  const writeSub = JlOta.onWriteRequest(async ({ dataBase64 }) => {
    if (disposed) return;
    try {
      await device.writeCharacteristicWithoutResponseForService(
        JlOta.JL_OTA_UUIDS.service,
        JlOta.JL_OTA_UUIDS.write,
        dataBase64 // base64 in, ble-plx writes the raw bytes
      );
    } catch (e) {
      console.warn('AE01 write failed', e);
    }
  });

  // 3) Progress + lifecycle.
  const progressSub = JlOta.onProgress(({ type, progress }) =>
    console.log(`OTA phase ${type}: ${progress.toFixed(1)}%`)
  );

  try {
    const result = await JlOta.startOta({
      deviceAddress: device.id,        // on Android, Device.id is the MAC
      filePath,
      mtu: 20,                         // bump after negotiating a larger MTU (see below)
    });
    console.log('OTA complete', result);
  } finally {
    disposed = true;
    // Do NOT call notifySub.remove() — see the warning below. Expo event
    // subscriptions (write/progress) are unrelated to ble-plx and safe to remove.
    writeSub.remove();
    progressSub.remove();
    JlOta.release();
  }
}
```

> ⚠️ **Never call `.remove()` on a ble-plx `monitorCharacteristicForService` subscription.**
> On `react-native-ble-plx` 3.5.0, cancelling a characteristic monitor
> (`subscription.remove()` → native `cancelTransaction`) rejects the monitor's
> promise with a null error code and **crashes the app natively**
> (`NullPointerException` in `PromiseImpl.reject`) — regardless of whether the
> device is still connected. This is not specific to OTA: it reproduces on any
> `monitorCharacteristicForService` subscription in the host app, including
> ones unrelated to this library, and has been observed crashing an app right
> after an OTA failure when disconnect cleanup called `.remove()` on an
> unrelated characteristic's monitor. Guard with a `disposed`/epoch flag as
> shown above instead, and let the dangling native monitor get cleaned up for
> free by `device.cancelConnection()` / `manager.destroy()`. Expo's own
> `EventSubscription.remove()` (for `onWriteRequest`/`onProgress`/etc.) is a
> different thing and is safe to call.

### Firmware sources

`startOta` accepts exactly one of:

```ts
JlOta.startOta({ deviceAddress, filePath: '/data/.../app.ufw' });   // local file
JlOta.startOta({ deviceAddress, fileBase64: '<base64 of .ufw>' });  // in-memory bytes
JlOta.startOta({ deviceAddress, url: 'https://cdn/app.ufw' });      // lib downloads first
```

### Going faster (MTU)

By default the SDK sends 20-byte packets (`mtu: 20`), which works on every device
but is slow. To speed up:

```ts
const negotiated = await device.requestMTU(247); // ble-plx
await JlOta.startOta({
  deviceAddress: device.id,
  filePath,
  mtu: negotiated.mtu - 3, // never exceed (MTU − 3)
});
```

---

## Dual-bank devices & reconnection

Some JieLi devices reboot into a loader and **re-advertise with a changed MAC
address** mid-OTA. When that happens the SDK emits `onOtaNeedReconnect` with the
new address; your JS must reconnect to it and tell the engine:

```ts
JlOta.onNeedReconnect(async ({ reconnectAddress }) => {
  if (!reconnectAddress) return;
  const reconnected = await ble.connectToDevice(reconnectAddress);
  await reconnected.discoverAllServicesAndCharacteristics();
  // re-attach the AE02 monitor on the new device, then:
  JlOta.setActiveDevice(reconnected.id); // re-point the SDK at the new device
  JlOta.notifyConnectionState(true);
});
```

`setActiveDevice` matters whenever `reconnectAddress` differs from the address you
started with: without it the SDK keeps its internal `BluetoothDevice` reference
pointed at the pre-reboot device even though your JS transport has moved on.

If your firmware engineer confirmed the device **does not** change address (single
bank), you can ignore `onOtaNeedReconnect`. Confirm the OTA mode with them — this is
the single most common source of "OTA stalls after ~95%" issues.

---

## API reference

### Functions

| Function | Description |
|---|---|
| `configure(options)` | Set engine options (see below). Optional. |
| `startOta(options): Promise<OtaResult>` | Start OTA; resolves on success, rejects on error/cancel. |
| `cancelOta(): Promise<void>` | Cancel the running OTA. |
| `notifyData(base64)` | Forward an AE02 notification into the SDK. |
| `notifyConnectionState(connected)` | Report the BLE link up/down. |
| `setActiveDevice(address)` | Re-point the SDK at a new MAC after a dual-bank reconnect. |
| `isOta(): boolean` | True while an OTA is running. |
| `getDeviceInfo(): Promise<DeviceInfo \| null>` | Cached firmware version info. |
| `release()` | Free native resources. |

### `configure` / `startOta` options

| Key | Type | Default | Notes |
|---|---|---|---|
| `deviceAddress` | `string` | — | **Required for `startOta`.** Device MAC. |
| `filePath` / `fileBase64` / `url` | `string` | — | Firmware source (pick one). |
| `mtu` | `number` | `20` | Bytes per write; ≤ negotiated MTU − 3. |
| `useSpp` | `boolean` | `false` | Use classic SPP instead of BLE. |
| `useAuthDevice` | `boolean` | `false` | Device authentication (ask firmware eng.). |
| `useReconnect` | `boolean` | `false` | Let the SDK manage reconnect (advanced). |
| `timeoutMs` | `number` | SDK default | Command timeout. |
| `bleIntervalMs` | `number` | SDK default | Connection-interval hint. |

### Event subscriptions

Each returns an `EventSubscription` — call `.remove()` when done.

`onWriteRequest`, `onProgress`, `onStateChange`, `onNeedReconnect`,
`onConnectRequest`, `onDisconnectRequest`, `onConnectionStateChange`,
`onMandatoryUpgrade`, `onError`.

### Constants

`JL_OTA_UUIDS` `{ service, write, notify, clientConfig }`, `JL_MTU_MIN` (20),
`JL_MTU_MAX` (509).

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `ERR_BAD_ADDRESS` | `deviceAddress` is not a valid MAC. Use `device.id` on Android. |
| OTA never starts / times out | AE02 monitor not attached, or `onOtaWriteRequest` not writing to AE01. |
| Stalls near the end, then errors | Dual-bank device changed address — handle `onOtaNeedReconnect`. |
| `Direct local .aar … not supported` at build | See [build notes](docs/JL_OTA_INTERNALS.md#packaging-the-aar). |
| Crash on x86 emulator only | Unsupported ABI — test on arm64 or a real device. |
| Native crash (NPE in `PromiseImpl.reject`) after/around OTA | Something called `.remove()` on a ble-plx characteristic monitor. Never do this — see the warning in [Quick start](#quick-start). |
| `[20481] SUB_ERR_AUTH_DEVICE` immediately | Firmware rejected the auth handshake. Toggle `useAuthDevice`; if **both** true/false fail, the firmware likely needs a custom auth key this SDK can't supply — ask your firmware engineer. |

Enable verbose SDK logs by checking `logcat` for the `JL_*` tags during OTA.

---

## License

MIT (this wrapper). The bundled JieLi `jl_bt_ota` SDK is © JieLi Technology and
governed by its own license — see <https://gitee.com/Jieli-Tech/Android-JL_OTA>.
