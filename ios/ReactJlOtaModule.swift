import ExpoModulesCore

/**
 * iOS is not supported: the JieLi OTA SDK is an Android-only library
 * (`jl_bt_ota_*.aar`). This stub keeps the JS API importable on iOS but every
 * OTA call rejects with `ERR_UNSUPPORTED_PLATFORM`. A native iOS OTA SDK from
 * JieLi would be required to implement this.
 */
public class ReactJlOtaModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ReactJlOta")

    Constants([
      "SERVICE_UUID": "0000ae00-0000-1000-8000-00805f9b34fb",
      "WRITE_CHARACTERISTIC_UUID": "0000ae01-0000-1000-8000-00805f9b34fb",
      "NOTIFY_CHARACTERISTIC_UUID": "0000ae02-0000-1000-8000-00805f9b34fb",
      "CLIENT_CHARACTERISTIC_CONFIG_UUID": "00002902-0000-1000-8000-00805f9b34fb",
      "MTU_MIN": 20,
      "MTU_MAX": 509
    ])

    Events(
      "onOtaWriteRequest",
      "onOtaProgress",
      "onOtaStateChange",
      "onOtaNeedReconnect",
      "onOtaConnectRequest",
      "onOtaDisconnectRequest",
      "onOtaConnectionStateChange",
      "onOtaMandatoryUpgrade",
      "onOtaError"
    )

    Function("configure") { (_: [String: Any]) in }
    Function("notifyData") { (_: String) in }
    Function("notifyConnectionState") { (_: Bool) in }
    Function("isOta") { () -> Bool in false }
    Function("release") {}

    AsyncFunction("startOta") { (_: [String: Any], promise: Promise) in
      promise.reject("ERR_UNSUPPORTED_PLATFORM", "JieLi OTA is only supported on Android")
    }
    AsyncFunction("cancelOta") { (promise: Promise) in promise.resolve(nil) }
    AsyncFunction("getDeviceInfo") { (promise: Promise) in promise.resolve(nil) }
  }
}
