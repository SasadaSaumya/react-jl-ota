package com.astrivix.reactjlota

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import com.astrivix.reactjlota.ble.BleManager
import com.astrivix.reactjlota.ble.interfaces.BleEventCallback
import com.astrivix.reactjlota.tool.AppUtil
import com.jieli.jl_bt_ota.impl.BluetoothOTAManager
import com.jieli.jl_bt_ota.util.JL_Log
import java.util.UUID

/**
 * Native-owned BLE engine for the JieLi OTA SDK. Extends [BluetoothOTAManager] and
 * implements its 5 transport hooks against a real [BleManager] (scan/GATT/write
 * queue) instead of delegating them to JavaScript — the design proven in the
 * `expo-jl-ota` reference library. Ported 1:1 from its `OtaManager`: the SDK never
 * touches BLE directly, but unlike the previous JS-bridge design, neither does the
 * host app — [BleManager] owns scanning, connecting, and writing entirely in native
 * code.
 */
class JlOtaEngine(context: Context, targetMac: String, deviceName: String = "") : BluetoothOTAManager(context) {

    companion object {
        // Named LOG_TAG, not TAG: BluetoothOTAManager (Java superclass) declares its
        // own "TAG" field, and a Kotlin companion constant of the same name triggers
        // https://youtrack.jetbrains.com/issue/KT-56386 (ambiguous field resolution).
        private const val LOG_TAG = "JlOtaEngine"
        private const val SCAN_TIMEOUT = 20 * 1000L
    }

    val mac: String get() = bleManager.mac

    private val bleManager = BleManager(context, targetMac, deviceName)

    init {
        bleManager.registerBleEventCallback(object : BleEventCallback() {
            override fun onBleConnection(device: BluetoothDevice, status: Int) {
                onBtDeviceConnection(device, AppUtil.changeConnectStatus(status))
            }

            override fun onBleDataNotification(device: BluetoothDevice, serviceUuid: UUID?, characteristicsUuid: UUID?, data: ByteArray?) {
                onReceiveDeviceData(device, data)
            }

            override fun onBleDataBlockChanged(device: BluetoothDevice, block: Int, status: Int) {
                onMtuChanged(getConnectedBluetoothGatt(), block, status)
            }
        })
        startLeScan(SCAN_TIMEOUT)
    }

    /** Scan for [mac] and auto-connect once found (mirrors the reference's constructor-time scan). */
    fun startLeScan(timeout: Long = SCAN_TIMEOUT) {
        bleManager.startLeScan(timeout)
    }

    /** Native dual-bank reconnect: scan for [address] and reconnect, no JS involvement. */
    fun reConnect(address: String?, isUseAdv: Boolean) {
        if (address.isNullOrEmpty()) return
        bleManager.reconnectDevice(address, isUseAdv)
    }

    /** True once the native GATT link to [mac] is up. */
    fun isConnected(): Boolean = bleManager.getConnectedBtDevice() != null

    override fun getConnectedDevice(): BluetoothDevice? = bleManager.getConnectedBtDevice()

    override fun getConnectedBluetoothGatt(): BluetoothGatt? {
        val device = getConnectedDevice() ?: return null
        return bleManager.getConnectedBtGatt(device)
    }

    override fun connectBluetoothDevice(bluetoothDevice: BluetoothDevice?) {
        bleManager.connectBleDevice(bluetoothDevice)
    }

    override fun disconnectBluetoothDevice(bluetoothDevice: BluetoothDevice?) {
        bleManager.disconnectBleDevice(bluetoothDevice)
    }

    override fun sendDataToDevice(bluetoothDevice: BluetoothDevice?, bytes: ByteArray?): Boolean {
        if (bluetoothDevice == null || bytes == null || bytes.isEmpty()) return false
        bleManager.writeDataByBleAsync(bluetoothDevice, BleManager.BLE_UUID_SERVICE, BleManager.BLE_UUID_WRITE, bytes) { device, _, _, result, _ ->
            JL_Log.d(LOG_TAG, "sendDataToDevice result - device: ${device.address}, success: $result")
        }
        return true
    }

    override fun release() {
        super.release()
        bleManager.destroy()
    }
}
