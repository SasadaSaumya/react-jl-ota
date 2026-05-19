// Reexport the native module. On web, it will be resolved to ReactJlOtaModule.web.ts
// and on native platforms to ReactJlOtaModule.ts
export { default } from './ReactJlOtaModule';
export { default as ReactJlOtaView } from './ReactJlOtaView';
export * from  './ReactJlOta.types';
