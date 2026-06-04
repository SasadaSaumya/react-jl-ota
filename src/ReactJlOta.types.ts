/**
 * Public types for react-jl-ota.
 *
 * The library wraps the JieLi (杰理) `jl_bt_ota` Android SDK and drives the OTA
 * (RCSP) protocol natively, while the BLE transport stays in your JavaScript
 * react-native-ble-plx code. See README.md for the wiring.
 */

/** Tunable options for the OTA engine. All fields optional. */
export type ReactJlOtaConfig = {
  /** Use SPP (classic BT) instead of BLE. Default: false (BLE). */
  useSpp?: boolean;
  /** Enable device authentication. Confirm with your firmware engineer. Default: false. */
  useAuthDevice?: boolean;
  /** Let the SDK manage reconnection. Default: false (JS handles reconnect). */
  useReconnect?: boolean;
  /**
   * Max bytes per BLE write the SDK produces. Must be <= (negotiated MTU − 3).
   * Default: 20 (always safe). Raise it (e.g. to the value you negotiated with
   * ble-plx) for faster transfers.
   */
  mtu?: number;
  /** Command timeout in ms. Default comes from the SDK. */
  timeoutMs?: number;
  /** BLE connection interval hint in ms. */
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
    /** MAC address of the already-connected device, e.g. "A1:B2:C3:D4:E5:F6". */
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

/** Emitted when the SDK needs bytes written to the AE01 characteristic. */
export type OtaWriteRequestPayload = {
  deviceAddress: string | null;
  /** Base64-encoded bytes to write to AE01 (write-without-response). */
  dataBase64: string;
};

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
  /** Address the device will advertise with after rebooting into the loader. */
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
  onOtaWriteRequest: (payload: OtaWriteRequestPayload) => void;
  onOtaProgress: (payload: OtaProgressPayload) => void;
  onOtaStateChange: (payload: OtaStateChangePayload) => void;
  onOtaNeedReconnect: (payload: OtaNeedReconnectPayload) => void;
  onOtaConnectRequest: (payload: OtaSimpleDevicePayload) => void;
  onOtaDisconnectRequest: (payload: OtaSimpleDevicePayload) => void;
  onOtaConnectionStateChange: (payload: OtaConnectionStatePayload) => void;
  onOtaMandatoryUpgrade: (payload: OtaSimpleDevicePayload) => void;
  onOtaError: (payload: OtaErrorPayload) => void;
};
