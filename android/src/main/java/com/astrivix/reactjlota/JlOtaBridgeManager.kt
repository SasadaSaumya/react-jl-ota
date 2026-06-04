package com.astrivix.reactjlota

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import com.jieli.jl_bt_ota.constant.StateCode
import com.jieli.jl_bt_ota.impl.BluetoothOTAManager
import com.jieli.jl_bt_ota.model.base.BaseError

/**
 * Bridges the JieLi [BluetoothOTAManager] (which only implements the RCSP/OTA
 * protocol) onto a BLE transport that lives in JavaScript (react-native-ble-plx).
 *
 * The JieLi SDK never touches the GATT connection directly here. Instead:
 *  - When the SDK wants to send bytes it calls [sendDataToDevice]; we forward the
 *    bytes to JS via [TransportDelegate.onWriteRequest] and JS writes them to the
 *    AE01 characteristic.
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
    return delegate.onWriteRequest(bluetoothDevice?.address, bytes)
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

  // endregion
}
