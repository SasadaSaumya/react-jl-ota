import { NativeModule, requireNativeModule } from 'expo';

import type {
  DeviceInfo,
  OtaResult,
  ReactJlOtaConfig,
  ReactJlOtaModuleEvents,
  StartOtaOptions,
} from './ReactJlOta.types';

declare class ReactJlOtaModule extends NativeModule<ReactJlOtaModuleEvents> {
  // GATT profile constants (also useful for ble-plx subscriptions).
  readonly SERVICE_UUID: string;
  readonly WRITE_CHARACTERISTIC_UUID: string;
  readonly NOTIFY_CHARACTERISTIC_UUID: string;
  readonly CLIENT_CHARACTERISTIC_CONFIG_UUID: string;
  readonly MTU_MIN: number;
  readonly MTU_MAX: number;

  configure(options: ReactJlOtaConfig): void;
  startOta(options: StartOtaOptions): Promise<OtaResult>;
  cancelOta(): Promise<void>;
  /** Feed an AE02 notification (base64) into the SDK. */
  notifyData(dataBase64: string): void;
  /**
   * Report whether the AE01 write requested via the `onOtaWriteRequest` event
   * actually completed. Call this as soon as your write's promise settles —
   * the native side blocks (with a bounded timeout) waiting for this before
   * letting the SDK proceed, so its reply-timeout clock reflects real
   * transmission time instead of just JS bridge dispatch time.
   */
  notifyWriteResult(success: boolean): void;
  /** Report the BLE link going up (true) or down (false). */
  notifyConnectionState(connected: boolean): void;
  /**
   * Re-point the SDK at the device now at `address` (its MAC). Call after a
   * dual-bank reconnect where the device re-advertises with a changed address.
   */
  setActiveDevice(address: string): void;
  isOta(): boolean;
  getDeviceInfo(): Promise<DeviceInfo | null>;
  release(): void;
}

// Loads the native module object from the JSI.
export default requireNativeModule<ReactJlOtaModule>('ReactJlOta');
