package com.astrivix.reactjlota

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanSettings
import android.util.Base64
import com.jieli.jl_bt_ota.constant.BluetoothConstant
import com.jieli.jl_bt_ota.constant.ErrorCode
import com.jieli.jl_bt_ota.constant.StateCode
import com.jieli.jl_bt_ota.interfaces.BtEventCallback
import com.jieli.jl_bt_ota.interfaces.IUpgradeCallback
import com.jieli.jl_bt_ota.model.BluetoothOTAConfigure
import com.jieli.jl_bt_ota.model.base.BaseError
import com.jieli.jl_bt_ota.model.response.TargetInfoResponse
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.net.URL

/**
 * Expo native module exposing the JieLi BLE OTA flow to JavaScript.
 *
 * The module never opens a GATT connection itself. The host app keeps its
 * react-native-ble-plx connection and:
 *   1. subscribes to the AE02 notify characteristic and forwards every packet
 *      via [notifyData];
 *   2. listens for the `onOtaWriteRequest` event and writes the bytes to AE01;
 *   3. calls [startOta] once the device is connected.
 */
class ReactJlOtaModule : Module(), JlOtaBridgeManager.TransportDelegate {

  private var bridge: JlOtaBridgeManager? = null

  /** Promise for the in-flight OTA, resolved on completion / rejected on error. */
  private var otaPromise: Promise? = null

  /** MAC address of the device currently being upgraded. */
  private var activeAddress: String? = null

  private val btEventCallback = object : BtEventCallback() {
    override fun onConnection(device: BluetoothDevice?, status: Int) {
      sendEvent(
        "onOtaConnectionStateChange",
        mapOf("deviceAddress" to device?.address, "status" to status)
      )
    }

    override fun onMandatoryUpgrade(device: BluetoothDevice?) {
      sendEvent("onOtaMandatoryUpgrade", mapOf("deviceAddress" to device?.address))
    }
  }

  override fun definition() = ModuleDefinition {
    Name("ReactJlOta")

    Constants(
      // JieLi OTA GATT profile — the host app subscribes/writes these in ble-plx.
      "SERVICE_UUID" to "0000ae00-0000-1000-8000-00805f9b34fb",
      "WRITE_CHARACTERISTIC_UUID" to "0000ae01-0000-1000-8000-00805f9b34fb",
      "NOTIFY_CHARACTERISTIC_UUID" to "0000ae02-0000-1000-8000-00805f9b34fb",
      "CLIENT_CHARACTERISTIC_CONFIG_UUID" to "00002902-0000-1000-8000-00805f9b34fb",
      "MTU_MIN" to BluetoothConstant.BLE_MTU_MIN,
      "MTU_MAX" to BluetoothConstant.BLE_MTU_MAX
    )

    Events(
      "onOtaWriteRequest",          // { deviceAddress, dataBase64 } -> write to AE01
      "onOtaProgress",              // { deviceAddress, type, progress }
      "onOtaStateChange",           // { deviceAddress, state }
      "onOtaNeedReconnect",         // { deviceAddress, reconnectAddress, isNewWay }
      "onOtaConnectRequest",        // { deviceAddress }
      "onOtaDisconnectRequest",     // { deviceAddress }
      "onOtaConnectionStateChange", // { deviceAddress, status }
      "onOtaMandatoryUpgrade",      // { deviceAddress }
      "onOtaError"                  // { code, subCode, message }
    )

    OnCreate {
      ensureBridge()
    }

    OnDestroy {
      releaseBridge()
    }

    /**
     * Configure the OTA engine. Safe to call multiple times; the latest config
     * wins. All keys are optional.
     */
    Function("configure") { options: Map<String, Any?> ->
      applyConfigure(options)
    }

    /**
     * Start an OTA. `options` must contain `deviceAddress` and exactly one
     * firmware source: `filePath`, `fileBase64`, or `url`.
     */
    AsyncFunction("startOta") { options: Map<String, Any?>, promise: Promise ->
      startOtaInternal(options, promise)
    }

    AsyncFunction("cancelOta") { promise: Promise ->
      bridge?.cancelOTA()
      promise.resolve(null)
    }

    /** Push an AE02 notification (base64) received in JS into the SDK. */
    Function("notifyData") { dataBase64: String ->
      val bytes = Base64.decode(dataBase64, Base64.NO_WRAP)
      bridge?.feedReceivedData(bytes)
    }

    /** Tell the SDK the BLE link is up (true) or down (false). */
    Function("notifyConnectionState") { connected: Boolean ->
      bridge?.feedConnectionState(
        if (connected) StateCode.CONNECTION_OK else StateCode.CONNECTION_DISCONNECT
      )
    }

    /**
     * Re-point the SDK at a new [BluetoothDevice] resolved from `address`.
     * Call this after a dual-bank reconnect where the device re-advertises
     * with a changed MAC (`onOtaNeedReconnect.reconnectAddress`) — otherwise
     * the SDK keeps referencing the pre-reboot device internally even though
     * JS has moved the transport to the new one.
     */
    Function("setActiveDevice") { address: String ->
      val manager = ensureBridge()
      try {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address.uppercase())
        manager.setActiveDevice(device)
        activeAddress = address.uppercase()
      } catch (e: Exception) {
        // Invalid address — leave the previous active device in place.
      }
    }

    /** True while an OTA is running. */
    Function("isOta") {
      bridge?.isOTA ?: false
    }

    /** Read cached device info (firmware version, etc.) for the active device. */
    AsyncFunction("getDeviceInfo") { promise: Promise ->
      val info = bridge?.deviceInfo
      promise.resolve(info?.let { targetInfoToMap(it) })
    }

    Function("release") {
      releaseBridge()
    }
  }

  // region Bridge lifecycle

  private fun ensureBridge(): JlOtaBridgeManager {
    bridge?.let { return it }
    val context = appContext.reactContext
      ?: throw CodedException("ERR_NO_CONTEXT", "React context is not available", null)
    val manager = JlOtaBridgeManager(context.applicationContext, this)
    manager.registerBluetoothCallback(btEventCallback)
    // Sensible defaults; overridden by configure().
    manager.configure(defaultConfigure())
    bridge = manager
    return manager
  }

  private fun releaseBridge() {
    bridge?.let {
      it.unregisterBluetoothCallback(btEventCallback)
      it.release()
    }
    bridge = null
    otaPromise = null
    activeAddress = null
  }

  // endregion

  // region Configuration

  private fun defaultConfigure(): BluetoothOTAConfigure = BluetoothOTAConfigure.createDefault().apply {
    priority = BluetoothOTAConfigure.PREFER_BLE
    // JS owns the link; the SDK must not try to renegotiate the MTU.
    isNeedChangeMtu = false
    mtu = BluetoothConstant.BLE_MTU_MIN
    // The SDK does not auto-reconnect; JS handles reconnection during OTA.
    isUseReconnect = false
    isUseJLServer = false
    bleScanMode = ScanSettings.SCAN_MODE_LOW_LATENCY
  }

  private fun applyConfigure(options: Map<String, Any?>) {
    val manager = ensureBridge()
    val config = manager.bluetoothOption ?: defaultConfigure()
    (options["useSpp"] as? Boolean)?.let {
      config.priority = if (it) BluetoothOTAConfigure.PREFER_SPP else BluetoothOTAConfigure.PREFER_BLE
    }
    (options["useAuthDevice"] as? Boolean)?.let { config.isUseAuthDevice = it }
    (options["useReconnect"] as? Boolean)?.let { config.isUseReconnect = it }
    intOf(options["mtu"])?.let { config.mtu = it }
    intOf(options["timeoutMs"])?.let { config.timeoutMs = it }
    intOf(options["bleIntervalMs"])?.let { config.bleIntervalMs = it }
    manager.configure(config)
  }

  // endregion

  // region OTA

  private fun startOtaInternal(options: Map<String, Any?>, promise: Promise) {
    val manager = ensureBridge()

    if (manager.isOTA) {
      promise.reject(CodedException("ERR_OTA_IN_PROGRESS", "An OTA is already running", null))
      return
    }

    val address = (options["deviceAddress"] as? String)?.uppercase()
    if (address.isNullOrBlank()) {
      promise.reject(CodedException("ERR_BAD_ADDRESS", "deviceAddress is required", null))
      return
    }

    val device: BluetoothDevice = try {
      BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address)
    } catch (e: Exception) {
      promise.reject(CodedException("ERR_BAD_ADDRESS", "Invalid deviceAddress: $address", e))
      return
    }

    // Per-call config overrides (mtu/auth/...).
    applyConfigure(options)
    val config = manager.bluetoothOption ?: defaultConfigure()

    // Resolve the firmware source.
    try {
      resolveFirmware(options, config)
    } catch (e: Exception) {
      promise.reject(CodedException("ERR_FIRMWARE", e.message ?: "Failed to load firmware", e))
      return
    }
    manager.configure(config)

    activeAddress = address
    otaPromise = promise
    manager.setActiveDevice(device)
    // Mark the link as already connected (JS connected it before calling startOta).
    manager.feedConnectionState(StateCode.CONNECTION_OK)

    manager.startOTA(upgradeCallback)
  }

  /** Populate `firmwareFilePath` / `firmwareFileData` on the config. */
  private fun resolveFirmware(options: Map<String, Any?>, config: BluetoothOTAConfigure) {
    val filePath = options["filePath"] as? String
    val fileBase64 = options["fileBase64"] as? String
    val url = options["url"] as? String

    config.firmwareFileData = null
    config.firmwareFilePath = null

    when {
      !filePath.isNullOrBlank() -> {
        val f = File(filePath)
        if (!f.exists()) throw IllegalArgumentException("Firmware file not found: $filePath")
        config.firmwareFilePath = filePath
      }
      !fileBase64.isNullOrBlank() -> {
        config.firmwareFileData = Base64.decode(fileBase64, Base64.NO_WRAP)
      }
      !url.isNullOrBlank() -> {
        config.firmwareFilePath = downloadFirmware(url)
      }
      else -> throw IllegalArgumentException("Provide one of filePath, fileBase64 or url")
    }
  }

  /** Download firmware to the cache dir and return the local path. */
  private fun downloadFirmware(url: String): String {
    val context = appContext.reactContext
      ?: throw IllegalStateException("React context is not available")
    val dir = File(context.cacheDir, "jl_ota").apply { mkdirs() }
    val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "firmware.ufw" }
    val out = File(dir, name)
    URL(url).openStream().use { input ->
      out.outputStream().use { output -> input.copyTo(output) }
    }
    return out.absolutePath
  }

  private val upgradeCallback = object : IUpgradeCallback {
    override fun onStartOTA() {
      sendState("start")
    }

    override fun onNeedReconnect(addr: String?, isNewReconnectWay: Boolean) {
      sendEvent(
        "onOtaNeedReconnect",
        mapOf(
          "deviceAddress" to activeAddress,
          "reconnectAddress" to addr,
          "isNewWay" to isNewReconnectWay
        )
      )
      sendState("reconnect")
    }

    override fun onProgress(type: Int, progress: Float) {
      sendEvent(
        "onOtaProgress",
        mapOf("deviceAddress" to activeAddress, "type" to type, "progress" to progress)
      )
    }

    override fun onStopOTA() {
      sendState("stop")
      otaPromise?.resolve(
        mapOf("deviceAddress" to activeAddress, "status" to "completed")
      )
      otaPromise = null
    }

    override fun onCancelOTA() {
      sendState("cancel")
      otaPromise?.reject(CodedException("ERR_OTA_CANCELLED", "OTA was cancelled", null))
      otaPromise = null
    }

    override fun onError(error: BaseError?) {
      val code = error?.code ?: ErrorCode.ERR_UNKNOWN
      val subCode = error?.subCode ?: ErrorCode.ERR_UNKNOWN
      val message = error?.message ?: "Unknown OTA error"
      sendEvent(
        "onOtaError",
        mapOf("code" to code, "subCode" to subCode, "message" to message)
      )
      otaPromise?.reject(CodedException("ERR_OTA_FAILED", "[$subCode] $message", null))
      otaPromise = null
    }
  }

  private fun sendState(state: String) {
    sendEvent("onOtaStateChange", mapOf("deviceAddress" to activeAddress, "state" to state))
  }

  // endregion

  // region TransportDelegate (called by JlOtaBridgeManager on SDK threads)

  override fun onWriteRequest(deviceAddress: String?, data: ByteArray): Boolean {
    sendEvent(
      "onOtaWriteRequest",
      mapOf(
        "deviceAddress" to (deviceAddress ?: activeAddress),
        "dataBase64" to Base64.encodeToString(data, Base64.NO_WRAP)
      )
    )
    // We optimistically report success; JS reports real write failures by
    // disconnecting / not feeding a response, which the SDK times out on.
    return true
  }

  override fun onConnectRequest(deviceAddress: String?) {
    sendEvent("onOtaConnectRequest", mapOf("deviceAddress" to (deviceAddress ?: activeAddress)))
  }

  override fun onDisconnectRequest(deviceAddress: String?) {
    sendEvent("onOtaDisconnectRequest", mapOf("deviceAddress" to (deviceAddress ?: activeAddress)))
  }

  // endregion

  // region Helpers

  private fun intOf(value: Any?): Int? = when (value) {
    is Int -> value
    is Number -> value.toInt()
    else -> null
  }

  private fun targetInfoToMap(info: TargetInfoResponse): Map<String, Any?> = mapOf(
    "versionName" to info.versionName,
    "versionCode" to info.versionCode,
    "protocolVersion" to info.protocolVersion,
    "ubootVersionName" to info.ubootVersionName,
    "ubootVersionCode" to info.ubootVersionCode,
    "sdkType" to info.sdkType,
    "isSupportDoubleBackup" to info.isSupportDoubleBackup
  )

  // endregion
}
