package com.astrivix.reactjlota

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import com.jieli.jl_bt_ota.constant.StateCode
import com.jieli.jl_bt_ota.impl.BluetoothOTAManager
import com.jieli.jl_bt_ota.model.base.BaseError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bridges the JieLi [BluetoothOTAManager] (which only implements the RCSP/OTA
 * protocol) onto a BLE transport that lives in JavaScript (react-native-ble-plx).
 *
 * The JieLi SDK never touches the GATT connection directly here. Instead:
 *  - When the SDK wants to send bytes it calls [sendDataToDevice]; we forward the
 *    bytes to JS via [TransportDelegate.onWriteRequest] and JS writes them to the
 *    AE01 characteristic. [sendDataToDevice] blocks (bounded by
 *    [WRITE_ACK_TIMEOUT_MS]) until JS reports the real GATT write outcome via
 *    [feedWriteResult], so the SDK's own reply-timeout clock — which starts once
 *    this call returns — reflects when the bytes actually left the phone instead
 *    of when the JS bridge merely accepted the request. See [feedWriteResult].
 *  - When JS receives an AE02 notification it calls [feedReceivedData], which we
 *    push into the SDK through [onReceiveDeviceData].
 *  - Connection up/down is signalled from JS through [feedConnectionState].
 *
 * Because JS owns the GATT, [getConnectedBluetoothGatt] returns null and BLE MTU
 * changes are disabled in the SDK configuration; the per-packet payload size is
 * fixed by the configured MTU instead.
 */
class JlOtaBridgeManager(
  context: Context,
  private val delegate: TransportDelegate
) : BluetoothOTAManager(context) {

  companion object {
    /**
     * How long [sendDataToDevice] waits for [feedWriteResult] before giving up
     * and reporting the write as failed. Matches the JieLi reference Android
     * app's own per-write send timeout (`SEND_DATA_MAX_TIMEOUT`), so we're not
     * more generous than the vendor's own proven integration.
     *
     * This wait is bounded on purpose: [feedWriteResult] is always invoked from
     * a different thread (the RN bridge, driven by ble-plx's native GATT
     * callback) than whatever thread the SDK calls [sendDataToDevice] from, so
     * there is no code path where this thread's own progress is required to
     * unblock the latch. But even if that assumption is ever wrong, a bounded
     * wait degrades to "this write failed" — which the SDK already knows how to
     * handle — rather than hanging forever.
     */
    private const val WRITE_ACK_TIMEOUT_MS = 8000L
  }

  private val pendingWriteLock = Object()
  private var pendingWriteLatch: CountDownLatch? = null
  private var pendingWriteResult: Boolean = false

  /** Callbacks the module implements to talk to JavaScript. */
  interface TransportDelegate {
    /** The SDK produced [data] that must be written to the AE01 characteristic. */
    fun onWriteRequest(deviceAddress: String?, data: ByteArray): Boolean

    /** The SDK asked to (re)connect the device. JS owns the connection. */
    fun onConnectRequest(deviceAddress: String?)

    /** The SDK asked to disconnect the device. */
    fun onDisconnectRequest(deviceAddress: String?)
  }

  /**
   * The device currently being upgraded. Resolved from the MAC address passed in
   * from JS so the SDK has a real [BluetoothDevice] to attribute events to.
   */
  @Volatile
  var activeDevice: BluetoothDevice? = null
    private set

  fun setActiveDevice(device: BluetoothDevice?) {
    activeDevice = device
  }

  // region Transport implementation required by IBluetoothManager

  override fun getConnectedDevice(): BluetoothDevice? = activeDevice

  // JS owns the GATT; the SDK only needs it for MTU changes, which are disabled.
  override fun getConnectedBluetoothGatt(): BluetoothGatt? = null

  override fun connectBluetoothDevice(bluetoothDevice: BluetoothDevice?) {
    delegate.onConnectRequest(bluetoothDevice?.address)
  }

  override fun disconnectBluetoothDevice(bluetoothDevice: BluetoothDevice?) {
    delegate.onDisconnectRequest(bluetoothDevice?.address)
  }

  override fun sendDataToDevice(bluetoothDevice: BluetoothDevice?, bytes: ByteArray?): Boolean {
    if (bytes == null) return false

    val latch = CountDownLatch(1)
    synchronized(pendingWriteLock) {
      pendingWriteLatch = latch
      pendingWriteResult = false
    }

    val dispatched = delegate.onWriteRequest(bluetoothDevice?.address, bytes)
    if (!dispatched) {
      synchronized(pendingWriteLock) { pendingWriteLatch = null }
      return false
    }

    val ackedInTime = latch.await(WRITE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    return synchronized(pendingWriteLock) {
      val result = ackedInTime && pendingWriteResult
      pendingWriteLatch = null
      result
    }
  }

  // endregion

  // region Inbound events pushed from JS

  /** Feed an AE02 notification received in JS into the SDK. */
  fun feedReceivedData(data: ByteArray) {
    onReceiveDeviceData(activeDevice, data)
  }

  /**
   * Tell the SDK the BLE link came up or went down.
   * @param status one of [StateCode.CONNECTION_OK] / [StateCode.CONNECTION_DISCONNECT].
   */
  fun feedConnectionState(status: Int) {
    onBtDeviceConnection(activeDevice, status)
  }

  /** Surface a transport-level error (e.g. a failed write) into the SDK. */
  fun feedError(error: BaseError) {
    onError(error)
  }

  /**
   * Report whether the write most recently requested via
   * [TransportDelegate.onWriteRequest] actually completed on the GATT link.
   * Unblocks the [sendDataToDevice] call currently waiting on it, if any. A
   * call with no matching in-flight write (already timed out, or none was
   * requested) is a no-op.
   */
  fun feedWriteResult(success: Boolean) {
    synchronized(pendingWriteLock) {
      pendingWriteResult = success
      pendingWriteLatch?.countDown()
    }
  }

  // endregion
}
