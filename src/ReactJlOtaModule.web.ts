import { registerWebModule, NativeModule } from 'expo';

import { ReactJlOtaModuleEvents } from './ReactJlOta.types';

class ReactJlOtaModule extends NativeModule<ReactJlOtaModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(ReactJlOtaModule, 'ReactJlOtaModule');
