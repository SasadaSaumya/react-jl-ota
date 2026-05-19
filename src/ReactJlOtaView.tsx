import { requireNativeView } from 'expo';
import * as React from 'react';

import { ReactJlOtaViewProps } from './ReactJlOta.types';

const NativeView: React.ComponentType<ReactJlOtaViewProps> =
  requireNativeView('ReactJlOta');

export default function ReactJlOtaView(props: ReactJlOtaViewProps) {
  return <NativeView {...props} />;
}
