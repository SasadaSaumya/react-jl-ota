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
  configure(_options: ReactJlOtaConfig): void {}
  async startOta(_options: StartOtaOptions): Promise<OtaResult> {
    throw new Error(UNSUPPORTED);
  }
  async cancelOta(): Promise<void> {}
  isOta(): boolean {
    return false;
  }
  async getDeviceInfo(): Promise<DeviceInfo | null> {
    return null;
  }
  release(): void {}
}

export default registerWebModule(ReactJlOtaModule, 'ReactJlOtaModule');
