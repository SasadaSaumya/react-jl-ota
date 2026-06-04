import { useEffect, useState } from 'react';
import { Button, SafeAreaView, ScrollView, Text, View } from 'react-native';
import * as JlOta from 'react-jl-ota';

/**
 * Minimal demo of the react-jl-ota API surface.
 *
 * NOTE: a real OTA also needs a live react-native-ble-plx connection with the
 * AE02 notify characteristic forwarded into JlOta.notifyData() and the
 * onOtaWriteRequest event writing to AE01. See the README "Quick start" for the
 * full ble-plx wiring — this screen only demonstrates the OTA-engine side.
 */
export default function App() {
  const [progress, setProgress] = useState(0);
  const [state, setState] = useState<string>('idle');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const subs = [
      JlOta.onProgress(({ progress }) => setProgress(progress)),
      JlOta.onStateChange(({ state }) => setState(state)),
      JlOta.onWriteRequest(({ dataBase64 }) => {
        // In a real app: write dataBase64 to AE01 via react-native-ble-plx here.
        console.log('AE01 write request', dataBase64.length, 'b64 chars');
      }),
      JlOta.onError(({ subCode, message }) => setError(`[${subCode}] ${message}`)),
    ];
    return () => subs.forEach((s) => s.remove());
  }, []);

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView style={styles.container}>
        <Text style={styles.header}>react-jl-ota</Text>

        <Group name="GATT profile (for ble-plx)">
          <Text>service: {JlOta.JL_OTA_UUIDS.service}</Text>
          <Text>write:   {JlOta.JL_OTA_UUIDS.write}</Text>
          <Text>notify:  {JlOta.JL_OTA_UUIDS.notify}</Text>
          <Text>mtu: {JlOta.JL_MTU_MIN}–{JlOta.JL_MTU_MAX}</Text>
        </Group>

        <Group name="State">
          <Text>state: {state}</Text>
          <Text>progress: {progress.toFixed(1)}%</Text>
          <Text>isOta: {String(JlOta.isOta())}</Text>
          {error ? <Text style={styles.error}>error: {error}</Text> : null}
        </Group>

        <Group name="Actions">
          <Button
            title="Start OTA (needs connected device + file)"
            onPress={async () => {
              setError(null);
              try {
                await JlOta.startOta({
                  deviceAddress: 'A1:B2:C3:D4:E5:F6', // replace with device.id
                  filePath: '/sdcard/Download/app.ufw', // replace with a real path
                });
              } catch (e: any) {
                setError(e?.message ?? String(e));
              }
            }}
          />
          <Button title="Cancel OTA" onPress={() => JlOta.cancelOta()} />
        </Group>
      </ScrollView>
    </SafeAreaView>
  );
}

function Group(props: { name: string; children: React.ReactNode }) {
  return (
    <View style={styles.group}>
      <Text style={styles.groupHeader}>{props.name}</Text>
      {props.children}
    </View>
  );
}

const styles = {
  header: { fontSize: 30, margin: 20 },
  groupHeader: { fontSize: 20, marginBottom: 12 },
  group: { margin: 20, backgroundColor: '#fff', borderRadius: 10, padding: 20 },
  container: { flex: 1, backgroundColor: '#eee' },
  error: { color: '#c00' },
};
