import * as React from 'react';

import { ReactJlOtaViewProps } from './ReactJlOta.types';

export default function ReactJlOtaView(props: ReactJlOtaViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
