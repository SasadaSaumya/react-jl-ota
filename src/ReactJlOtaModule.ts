import { NativeModule, requireNativeModule } from 'expo';

import type {
  DeviceInfo,
  OtaResult,
  ReactJlOtaConfig,
  ReactJlOtaModuleEvents,
  StartOtaOptions,
} from './ReactJlOta.types';

declare class ReactJlOtaModule extends NativeModule<ReactJlOtaModuleEvents> {
  configure(options: ReactJlOtaConfig): void;
  /** Scans for and connects to `options.deviceAddress` natively, then runs the OTA. */
  startOta(options: StartOtaOptions): Promise<OtaResult>;
  cancelOta(): Promise<void>;
  isOta(): boolean;
  getDeviceInfo(): Promise<DeviceInfo | null>;
  release(): void;
}

// Loads the native module object from the JSI.
export default requireNativeModule<ReactJlOtaModule>('ReactJlOta');
