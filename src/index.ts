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
  ReactJlOtaConfig,
  StartOtaOptions,
} from './ReactJlOta.types';

export * from './ReactJlOta.types';

/** Low-level native module (escape hatch). Prefer the helpers below. */
export default ReactJlOta;

// ---- Imperative API ----

/** Apply OTA engine configuration (optional; sensible BLE defaults are used). */
export function configure(options: ReactJlOtaConfig): void {
  ReactJlOta.configure(options);
}

/**
 * Start an OTA. The module scans for and connects to `options.deviceAddress`
 * natively — no BLE setup is required in JS. Resolves when the upgrade
 * finishes, rejects on error/cancel.
 */
export function startOta(options: StartOtaOptions): Promise<OtaResult> {
  return ReactJlOta.startOta(options);
}

/** Cancel the in-flight OTA. */
export function cancelOta(): Promise<void> {
  return ReactJlOta.cancelOta();
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

/** Fires when a dual-bank device reboots mid-OTA; native reconnect is already in progress. */
export function onNeedReconnect(
  listener: (payload: OtaNeedReconnectPayload) => void
): EventSubscription {
  return ReactJlOta.addListener('onOtaNeedReconnect', listener);
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
