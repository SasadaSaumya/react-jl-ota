import { NativeModule, requireNativeModule } from 'expo';

import { ReactJlOtaModuleEvents } from './ReactJlOta.types';

declare class ReactJlOtaModule extends NativeModule<ReactJlOtaModuleEvents> {
  PI: number;
  hello(): string;
  setValueAsync(value: string): Promise<void>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ReactJlOtaModule>('ReactJlOta');
