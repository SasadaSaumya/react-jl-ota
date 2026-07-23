/**
 * Public types for react-jl-ota.
 *
 * The library wraps the JieLi (杰理) `jl_bt_ota` Android SDK and owns the BLE link
 * end to end natively — scanning, connecting, and writing all happen inside the
 * native module. You just supply a device address (however you obtained it) and a
 * firmware source; no GATT plumbing is required in JavaScript.
 */

/** Tunable options for the OTA engine. All fields optional. */
export type ReactJlOtaConfig = {
  /** Use SPP (classic BT) instead of BLE. Default: false (BLE). */
  useSpp?: boolean;
  /** Enable device authentication. Confirm with your firmware engineer. Default: true. */
  useAuthDevice?: boolean;
  /** Let the SDK manage reconnection awareness. Default: true. */
  useReconnect?: boolean;
  /** Max bytes per BLE write the SDK produces. Default: 500. */
  mtu?: number;
  /** Command timeout in ms. Default: 3000. */
  timeoutMs?: number;
  /** BLE connection interval hint in ms. Default: 500. */
  bleIntervalMs?: number;
};

/** Firmware source. Provide exactly one of filePath / fileBase64 / url. */
export type FirmwareSource =
  | { filePath: string; fileBase64?: never; url?: never }
  | { filePath?: never; fileBase64: string; url?: never }
  | { filePath?: never; fileBase64?: never; url: string };

/** Options for {@link startOta}. */
export type StartOtaOptions = ReactJlOtaConfig &
  FirmwareSource & {
    /** MAC address of the target device, e.g. "A1:B2:C3:D4:E5:F6". The module scans for and connects to it natively. */
    deviceAddress: string;
  };

export type OtaResult = {
  deviceAddress: string | null;
  status: 'completed';
};

/** Cached device info read from the firmware. */
export type DeviceInfo = {
  versionName: string | null;
  versionCode: number;
  protocolVersion: string | null;
  ubootVersionName: string | null;
  ubootVersionCode: number;
  sdkType: number;
  isSupportDoubleBackup: boolean;
};

export type OtaState = 'start' | 'reconnect' | 'stop' | 'cancel';

// ---- Event payloads ----

export type OtaProgressPayload = {
  deviceAddress: string | null;
  /** Phase indicator from the SDK (firmware-dependent). */
  type: number;
  /** 0–100. */
  progress: number;
};

export type OtaStateChangePayload = {
  deviceAddress: string | null;
  state: OtaState;
};

export type OtaNeedReconnectPayload = {
  deviceAddress: string | null;
  /** Address the device advertises with after rebooting into the loader (native reconnect already in progress). */
  reconnectAddress: string | null;
  isNewWay: boolean;
};

export type OtaConnectionStatePayload = {
  deviceAddress: string | null;
  /** JieLi StateCode: 0 disconnect, 1 ok, 2 failed, 3 connecting, 4 connected. */
  status: number;
};

export type OtaSimpleDevicePayload = {
  deviceAddress: string | null;
};

export type OtaErrorPayload = {
  code: number;
  subCode: number;
  message: string;
};

export type ReactJlOtaModuleEvents = {
  onOtaProgress: (payload: OtaProgressPayload) => void;
  onOtaStateChange: (payload: OtaStateChangePayload) => void;
  onOtaNeedReconnect: (payload: OtaNeedReconnectPayload) => void;
  onOtaConnectionStateChange: (payload: OtaConnectionStatePayload) => void;
  onOtaMandatoryUpgrade: (payload: OtaSimpleDevicePayload) => void;
  onOtaError: (payload: OtaErrorPayload) => void;
};
