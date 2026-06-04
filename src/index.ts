import type { EventSubscription } from 'expo-modules-core';

import ReactJlOta from './ReactJlOtaModule';
import type {
  DeviceInfo,
  OtaConnectionStatePayload,
  OtaErrorPayload,
  OtaNeedReconnectPayload,
  OtaProgressPayload,
  OtaResult,
  OtaSimpleDevicePayload,
  OtaStateChangePayload,
  OtaWriteRequestPayload,
  ReactJlOtaConfig,
  StartOtaOptions,
} from './ReactJlOta.types';

export * from './ReactJlOta.types';

/** Low-level native module (escape hatch). Prefer the helpers below. */
export default ReactJlOta;

// ---- GATT profile constants ----

/** JieLi OTA BLE service / characteristic UUIDs (subscribe & write these in ble-plx). */
export const JL_OTA_UUIDS = {
  service: ReactJlOta.SERVICE_UUID,
  write: ReactJlOta.WRITE_CHARACTERISTIC_UUID,
  notify: ReactJlOta.NOTIFY_CHARACTERISTIC_UUID,
  clientConfig: ReactJlOta.CLIENT_CHARACTERISTIC_CONFIG_UUID,
} as const;

export const JL_MTU_MIN = ReactJlOta.MTU_MIN;
export const JL_MTU_MAX = ReactJlOta.MTU_MAX;

// ---- Imperative API ----

/** Apply OTA engine configuration (optional; sensible BLE defaults are used). */
export function configure(options: ReactJlOtaConfig): void {
  ReactJlOta.configure(options);
}

/**
 * Start an OTA on an already-connected device. Resolves when the upgrade
 * finishes, rejects on error/cancel.
 *
 * You MUST already be: (1) connected via ble-plx, (2) subscribed to the AE02
 * notify characteristic and forwarding packets via {@link notifyData}, and
 * (3) handling the `onOtaWriteRequest` event by writing to AE01.
 */
export function startOta(options: StartOtaOptions): Promise<OtaResult> {
  return ReactJlOta.startOta(options);
}

/** Cancel the in-flight OTA. */
export function cancelOta(): Promise<void> {
  return ReactJlOta.cancelOta();
}

/**
 * Forward an AE02 notification to the OTA engine.
 * @param dataBase64 the notification value, base64-encoded (ble-plx gives this directly).
 */
export function notifyData(dataBase64: string): void {
  ReactJlOta.notifyData(dataBase64);
}

/** Tell the engine the BLE link is up (true) or down (false). */
export function notifyConnectionState(connected: boolean): void {
  ReactJlOta.notifyConnectionState(connected);
}

/** True while an OTA is running. */
export function isOta(): boolean {
  return ReactJlOta.isOta();
}

/** Read cached device info (firmware version, etc.). */
export function getDeviceInfo(): Promise<DeviceInfo | null> {
  return ReactJlOta.getDeviceInfo();
}

/** Release native resources. Call when you are done with OTA. */
export function release(): void {
  ReactJlOta.release();
}

// ---- Event helpers ----

/** Subscribe to write requests — write `payload.dataBase64` to AE01 here. */
export function onWriteRequest(
  listener: (payload: OtaWriteRequestPayload) => void
): EventSubscription {
  return ReactJlOta.addListener('onOtaWriteRequest', listener);
}

export function onProgress(
  listener: (payload: OtaProgressPayload) => void
): EventSubscription {
  return ReactJlOta.addListener('onOtaProgress', listener);
}

export function onStateChange(
  listener: (payload: OtaStateChangePayload) => void
): EventSubscription {
  return ReactJlOta.addListener('onOtaStateChange', listener);
}

export function onNeedReconnect(
  listener: (payload: OtaNeedReconnectPayload) => void
): EventSubscription {
  return ReactJlOta.addListener('onOtaNeedReconnect', listener);
}

export function onConnectRequest(
  listener: (payload: OtaSimpleDevicePayload) => void
): EventSubscription {
  return ReactJlOta.addListener('onOtaConnectRequest', listener);
}

export function onDisconnectRequest(
  listener: (payload: OtaSimpleDevicePayload) => void
): EventSubscription {
  return ReactJlOta.addListener('onOtaDisconnectRequest', listener);
}

export function onConnectionStateChange(
  listener: (payload: OtaConnectionStatePayload) => void
): EventSubscription {
  return ReactJlOta.addListener('onOtaConnectionStateChange', listener);
}

export function onMandatoryUpgrade(
  listener: (payload: OtaSimpleDevicePayload) => void
): EventSubscription {
  return ReactJlOta.addListener('onOtaMandatoryUpgrade', listener);
}

export function onError(
  listener: (payload: OtaErrorPayload) => void
): EventSubscription {
  return ReactJlOta.addListener('onOtaError', listener);
}
