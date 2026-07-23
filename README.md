# react-jl-ota

React Native / Expo OTA firmware-update library for **JieLi (杰理 / JL)** Bluetooth
chips, wrapping the official `jl_bt_ota` Android SDK.

The module **owns the BLE link end to end, natively** — scanning, connecting, GATT
writes, notification handling, MTU negotiation, and dual-bank reconnect all happen
inside the native module. Your JS just supplies a device address (however you
obtained it — your own scan, a paired-devices list, etc.) and a firmware source.
No `react-native-ble-plx` wiring, no GATT plumbing, no `notifyData`/write-request
event handling required.

> Platform support: **Android only.** The JieLi OTA SDK is an Android `.aar`.
> The library is importable on iOS/web but every OTA call rejects with
> `ERR_UNSUPPORTED_PLATFORM`.

---

## How it works

The JieLi `BluetoothOTAManager` is an *abstract transport* — it implements the RCSP/OTA
protocol but delegates the actual reading/writing of bytes to whoever embeds it. This
library implements that transport with its own native BLE engine (`JlOtaEngine` +
`ble/BleManager`, ported from JieLi's own proven reference Android integration):

```
 JS (your app)                          Native (react-jl-ota)
 ─────────────                          ──────────────────────
 startOta({ deviceAddress, … }) ──────▶  scan for deviceAddress
                                         connect GATT, discover services
                                         enable AE02 notifications, negotiate MTU
                                         BluetoothOTAManager.startOTA()
 onOtaProgress / resolve(startOta) ◀────  IUpgradeCallback
 onOtaNeedReconnect (informational) ◀──  native reconnect already in progress
```

For a deeper explanation of the SDK internals, the RCSP protocol, dual-bank
reconnection and error codes, see [`docs/JL_OTA_INTERNALS.md`](docs/JL_OTA_INTERNALS.md).

---

## Installation

This is a local Expo module that bundles the vendor AAR. The AAR is already in
`android/libs/jl_bt_ota_V1.11.0_11015-release.aar`.

In your app:

```bash
npm install react-jl-ota
npx expo prebuild   # config plugin / autolinking picks up the native module
```

The module requests its own BLE runtime permissions declaration
(`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and location on Android < 12) — your app
still needs to *request* these at runtime before calling `startOta`.

> **Min Android SDK 21.** The vendor AAR ships native `.so` files for
> `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` only.

---

## Quick start

```ts
import * as JlOta from 'react-jl-ota';

const progressSub = JlOta.onProgress(({ type, progress }) =>
  console.log(`OTA phase ${type}: ${progress.toFixed(1)}%`)
);
const errorSub = JlOta.onError(({ subCode, message }) =>
  console.warn(`OTA error [${subCode}] ${message}`)
);

try {
  const result = await JlOta.startOta({
    deviceAddress: 'A1:B2:C3:D4:E5:F6', // the device's MAC
    filePath: '/data/.../app.ufw',
  });
  console.log('OTA complete', result);
} finally {
  progressSub.remove();
  errorSub.remove();
  JlOta.release();
}
```

That's it — no scanning, connecting, or characteristic wiring needed; the module
does all of that natively once `startOta` is called.

### Firmware sources

`startOta` accepts exactly one of:

```ts
JlOta.startOta({ deviceAddress, filePath: '/data/.../app.ufw' });   // local file
JlOta.startOta({ deviceAddress, fileBase64: '<base64 of .ufw>' });  // in-memory bytes
JlOta.startOta({ deviceAddress, url: 'https://cdn/app.ufw' });      // lib downloads first
```

> **Prefer `filePath` over `url`.** The `url` convenience blocks OTA start on a
> plain, single-attempt HTTP fetch inside the native module — on a slow or
> flaky connection this can die with an opaque "unexpected end of stream"
> after minutes of apparent inactivity, and there's no retry/backoff. The
> JieLi reference Android app always downloads to a local file first (its own
> `DownloadFileUtil`, built on OkHttp with real progress) and only then starts
> OTA with a local path. Do the same: download with whatever HTTP client you
> already use (e.g. `expo-file-system`'s `File.downloadFileAsync`), then call
> `startOta({ filePath })`.

---

## Dual-bank devices & reconnection

Some JieLi devices reboot into a loader and **re-advertise with a changed MAC
address** mid-OTA. The native engine handles this itself: on `IUpgradeCallback.onNeedReconnect`
it scans for and reconnects to the new address using the ported `ReConnectHelper`,
without any JS involvement. `onOtaNeedReconnect` is emitted purely for visibility
(e.g. updating a "reconnecting…" UI state) — you don't need to act on it.

If your firmware engineer confirmed the device **does not** change address (single
bank), this event simply never fires.

---

## API reference

### Functions

| Function | Description |
|---|---|
| `configure(options)` | Set engine options (see below). Optional; can be called before a device address is known. |
| `startOta(options): Promise<OtaResult>` | Scan for, connect to, and upgrade `options.deviceAddress`. Resolves on success, rejects on error/cancel. |
| `cancelOta(): Promise<void>` | Cancel the running OTA. |
| `isOta(): boolean` | True while an OTA is running. |
| `getDeviceInfo(): Promise<DeviceInfo \| null>` | Cached firmware version info. |
| `release()` | Disconnect and free native resources. |

### `configure` / `startOta` options

| Key | Type | Default | Notes |
|---|---|---|---|
| `deviceAddress` | `string` | — | **Required for `startOta`.** Device MAC; the module scans for and connects to it. |
| `filePath` / `fileBase64` / `url` | `string` | — | Firmware source (pick one). |
| `mtu` | `number` | `500` | Requested MTU; the native engine negotiates the real GATT MTU itself. |
| `useSpp` | `boolean` | `false` | Use classic SPP instead of BLE. |
| `useAuthDevice` | `boolean` | `true` | Device authentication (ask firmware eng. if OTA fails at the auth step). |
| `useReconnect` | `boolean` | `true` | SDK reconnect awareness (native reconnect always runs regardless). |
| `timeoutMs` | `number` | `3000` | Command timeout. |
| `bleIntervalMs` | `number` | `500` | Connection-interval hint. |

### Event subscriptions

Each returns an `EventSubscription` — call `.remove()` when done.

`onProgress`, `onStateChange`, `onNeedReconnect`, `onConnectionStateChange`,
`onMandatoryUpgrade`, `onError`.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `ERR_BAD_ADDRESS` | `deviceAddress` is not a valid MAC. |
| `ERR_NO_CONTEXT` | Called before the module's React context was ready (very rare). |
| OTA never starts / times out | Runtime BLE permissions (`BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, plus location on Android < 12) not granted, or the device isn't advertising. |
| Stalls near the end, then errors | Dual-bank reconnect failed — check `onOtaNeedReconnect`/`onOtaError` logs; usually a scan/connect issue on the new address. |
| `Direct local .aar … not supported` at build | See [build notes](docs/JL_OTA_INTERNALS.md#packaging-the-aar). |
| Crash on x86 emulator only | Unsupported ABI — test on arm64 or a real device. |
| `[20481] SUB_ERR_AUTH_DEVICE` immediately | Firmware rejected the auth handshake. Toggle `useAuthDevice`; if **both** true/false fail, the firmware likely needs a custom auth key this SDK can't supply — ask your firmware engineer. |

Enable verbose SDK logs by checking `logcat` for the `JL_*` tags during OTA.

---

## License

MIT (this wrapper). The bundled JieLi `jl_bt_ota` SDK is © JieLi Technology and
governed by its own license — see <https://gitee.com/Jieli-Tech/Android-JL_OTA>.
