import { registerWebModule, NativeModule } from 'expo';

import type {
  DeviceInfo,
  OtaResult,
  ReactJlOtaConfig,
  ReactJlOtaModuleEvents,
  StartOtaOptions,
} from './ReactJlOta.types';

const UNSUPPORTED = 'JieLi OTA is not supported on web';

/** Web stub — OTA over BLE is not available in the browser. */
class ReactJlOtaModule extends NativeModule<ReactJlOtaModuleEvents> {
  SERVICE_UUID = '0000ae00-0000-1000-8000-00805f9b34fb';
  WRITE_CHARACTERISTIC_UUID = '0000ae01-0000-1000-8000-00805f9b34fb';
  NOTIFY_CHARACTERISTIC_UUID = '0000ae02-0000-1000-8000-00805f9b34fb';
  CLIENT_CHARACTERISTIC_CONFIG_UUID = '00002902-0000-1000-8000-00805f9b34fb';
  MTU_MIN = 20;
  MTU_MAX = 509;

  configure(_options: ReactJlOtaConfig): void {}
  async startOta(_options: StartOtaOptions): Promise<OtaResult> {
    throw new Error(UNSUPPORTED);
  }
  async cancelOta(): Promise<void> {}
  notifyData(_dataBase64: string): void {}
  notifyConnectionState(_connected: boolean): void {}
  setActiveDevice(_address: string): void {}
  isOta(): boolean {
    return false;
  }
  async getDeviceInfo(): Promise<DeviceInfo | null> {
    return null;
  }
  release(): void {}
}

export default registerWebModule(ReactJlOtaModule, 'ReactJlOtaModule');
