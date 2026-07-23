package com.astrivix.reactjlota

import android.bluetooth.BluetoothDevice
import android.util.Base64
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
 * Unlike the library's earlier JS-bridge design, the module now owns the BLE link
 * end to end via [JlOtaEngine] (native scan/connect/write, ported from the proven
 * `expo-jl-ota` reference). JS just supplies a `deviceAddress` and a firmware
 * source — no GATT plumbing required on the JS side.
 */
class ReactJlOtaModule : Module() {

  private var engine: JlOtaEngine? = null

  /** Promise for the in-flight OTA, resolved on completion / rejected on error. */
  private var otaPromise: Promise? = null

  /** MAC address of the device currently being upgraded. */
  private var activeAddress: String? = null

  /** Config tunables applied to whichever engine is current — set via [configure]. */
  private val configOverrides = mutableMapOf<String, Any?>()

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

    Events(
      "onOtaProgress",              // { deviceAddress, type, progress }
      "onOtaStateChange",           // { deviceAddress, state }
      "onOtaNeedReconnect",         // { deviceAddress, reconnectAddress, isNewWay }
      "onOtaConnectionStateChange", // { deviceAddress, status }
      "onOtaMandatoryUpgrade",      // { deviceAddress }
      "onOtaError"                  // { code, subCode, message }
    )

    OnDestroy {
      releaseEngine()
    }

    /**
     * Configure the OTA engine. Safe to call multiple times, and before a device
     * address is known — overrides are kept and applied to whichever engine is
     * (re)created next. All keys are optional.
     */
    Function("configure") { options: Map<String, Any?> ->
      configOverrides.putAll(options)
      engine?.let { applyConfigure(it, options) }
    }

    /**
     * Start an OTA. `options` must contain `deviceAddress` and exactly one
     * firmware source: `filePath`, `fileBase64`, or `url`. The module scans for
     * and connects to `deviceAddress` natively — no BLE setup is required in JS.
     */
    AsyncFunction("startOta") { options: Map<String, Any?>, promise: Promise ->
      startOtaInternal(options, promise)
    }

    AsyncFunction("cancelOta") { promise: Promise ->
      engine?.cancelOTA()
      promise.resolve(null)
    }

    /** True while an OTA is running. */
    Function("isOta") {
      engine?.isOTA ?: false
    }

    /** Read cached device info (firmware version, etc.) for the active device. */
    AsyncFunction("getDeviceInfo") { promise: Promise ->
      val info = engine?.deviceInfo
      promise.resolve(info?.let { targetInfoToMap(it) })
    }

    Function("release") {
      releaseEngine()
    }
  }

  // region Engine lifecycle

  /** Returns the engine for [address], recreating it if it currently targets a different device. */
  private fun ensureEngine(address: String): JlOtaEngine {
    val current = engine
    if (current != null && current.mac.equals(address, ignoreCase = true)) {
      return current
    }
    current?.let {
      it.unregisterBluetoothCallback(btEventCallback)
      it.release()
    }
    val context = appContext.reactContext
      ?: throw CodedException("ERR_NO_CONTEXT", "React context is not available", null)
    val created = JlOtaEngine(context.applicationContext, address)
    created.registerBluetoothCallback(btEventCallback)
    applyConfigure(created, configOverrides)
    engine = created
    return created
  }

  private fun releaseEngine() {
    engine?.let {
      it.unregisterBluetoothCallback(btEventCallback)
      it.release()
    }
    engine = null
    otaPromise = null
    activeAddress = null
  }

  // endregion

  // region Configuration

  private fun defaultConfigure(): BluetoothOTAConfigure = BluetoothOTAConfigure.createDefault().apply {
    priority = BluetoothOTAConfigure.PREFER_BLE
    isUseAuthDevice = true
    bleIntervalMs = 500
    timeoutMs = 3000
    mtu = 500
    // BleManager already renegotiates the real GATT MTU right after connecting
    // (see its onDescriptorWrite -> startChangeMtu); the SDK doing it again would
    // be redundant. Matches the reference's own proven config exactly.
    isNeedChangeMtu = false
    isUseReconnect = true
  }

  private fun applyConfigure(target: JlOtaEngine, options: Map<String, Any?>) {
    val config = target.bluetoothOption ?: defaultConfigure()
    (options["useSpp"] as? Boolean)?.let {
      config.priority = if (it) BluetoothOTAConfigure.PREFER_SPP else BluetoothOTAConfigure.PREFER_BLE
    }
    (options["useAuthDevice"] as? Boolean)?.let { config.isUseAuthDevice = it }
    (options["useReconnect"] as? Boolean)?.let { config.isUseReconnect = it }
    intOf(options["mtu"])?.let { config.mtu = it }
    intOf(options["timeoutMs"])?.let { config.timeoutMs = it }
    intOf(options["bleIntervalMs"])?.let { config.bleIntervalMs = it }
    target.configure(config)
  }

  // endregion

  // region OTA

  private fun startOtaInternal(options: Map<String, Any?>, promise: Promise) {
    val address = (options["deviceAddress"] as? String)?.uppercase()
    if (address.isNullOrBlank()) {
      promise.reject(CodedException("ERR_BAD_ADDRESS", "deviceAddress is required", null))
      return
    }

    val engine = try {
      ensureEngine(address)
    } catch (e: CodedException) {
      promise.reject(e)
      return
    }

    if (engine.isOTA) {
      promise.reject(CodedException("ERR_OTA_IN_PROGRESS", "An OTA is already running", null))
      return
    }

    // Per-call config overrides (mtu/auth/...), then resolve the firmware source.
    applyConfigure(engine, options)
    val config = engine.bluetoothOption ?: defaultConfigure()
    try {
      resolveFirmware(options, config)
    } catch (e: Exception) {
      promise.reject(CodedException("ERR_FIRMWARE", e.message ?: "Failed to load firmware", e))
      return
    }
    engine.configure(config)

    activeAddress = address
    otaPromise = promise

    var otaStarted = false
    if (engine.isConnected()) {
      // Reused engine already connected from a previous call to the same address.
      otaStarted = true
      engine.startOTA(upgradeCallback)
    } else {
      lateinit var connectGate: BtEventCallback
      connectGate = object : BtEventCallback() {
        override fun onConnection(device: BluetoothDevice?, status: Int) {
          if (status == StateCode.CONNECTION_OK && !otaStarted) {
            otaStarted = true
            engine.unregisterBluetoothCallback(connectGate)
            engine.startOTA(upgradeCallback)
          }
        }
      }
      engine.registerBluetoothCallback(connectGate)
    }
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

  /**
   * Download firmware to the cache dir and return the local path.
   *
   * This blocks the OTA start on a plain HTTP fetch with no back-off/retry.
   * Prefer downloading the firmware yourself (e.g. with a proper HTTP client
   * that has retry/backoff) and passing `filePath` instead — this is kept as
   * a convenience for simple cases, with generous but finite timeouts so a
   * stalled connection fails fast instead of hanging or dying with an opaque
   * "unexpected end of stream" after minutes of silence.
   */
  private fun downloadFirmware(url: String): String {
    val context = appContext.reactContext
      ?: throw IllegalStateException("React context is not available")
    val dir = File(context.cacheDir, "jl_ota").apply { mkdirs() }
    val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "firmware.ufw" }
    val out = File(dir, name)
    val connection = URL(url).openConnection().apply {
      connectTimeout = 15_000
      readTimeout = 30_000
    }
    connection.getInputStream().use { input ->
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
      // Native reconnect — scan for the (possibly changed, dual-bank) address and
      // reconnect without any JS involvement.
      engine?.reConnect(addr, isNewReconnectWay)
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
