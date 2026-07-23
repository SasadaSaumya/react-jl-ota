package com.astrivix.reactjlota.ble.model

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.content.Context
import com.astrivix.reactjlota.ble.SendBleDataThread
import com.astrivix.reactjlota.ble.interfaces.IBleOp
import com.astrivix.reactjlota.ble.interfaces.OnThreadStateListener
import com.astrivix.reactjlota.ble.interfaces.OnWriteDataCallback
import com.astrivix.reactjlota.tool.AppUtil
import com.jieli.jl_bt_ota.constant.BluetoothConstant
import com.jieli.jl_bt_ota.util.CHexConver
import com.jieli.jl_bt_ota.util.JL_Log
import java.util.UUID

/** Per-connection state: the GATT handle, negotiated MTU, and its dedicated write-serialization thread. */
class BleDevice(private val context: Context, val gatt: BluetoothGatt) {
    private val tag = "BleManager"
    private var mtu: Int = BluetoothConstant.BLE_MTU_MIN
    var connectedTime: Long = 0

    private var sendDataThread: SendBleDataThread? = null

    fun getMtu(): Int {
        var realMtu = mtu
        if (realMtu > 128) {
            realMtu -= 6
        }
        return realMtu
    }

    fun setMtu(mtu: Int) {
        this.mtu = mtu
    }

    fun startSendDataThread() {
        val current = sendDataThread
        if (current == null || !current.isRunning()) {
            val thread = SendBleDataThread(
                object : IBleOp {
                    override fun getBleMtu(): Int = getMtu()

                    override fun writeDataByBle(gatt: BluetoothGatt, serviceUUID: UUID, characteristicUUID: UUID, data: ByteArray): Boolean =
                        writeDataToDeviceByBle(gatt, serviceUUID, characteristicUUID, data)
                },
                object : OnThreadStateListener {
                    override fun onStart(id: Long, name: String) {}

                    override fun onEnd(id: Long, name: String) {
                        if (sendDataThread?.id == id) {
                            sendDataThread = null
                        }
                    }
                }
            )
            sendDataThread = thread
            thread.start()
        }
    }

    fun stopSendDataThread() {
        sendDataThread?.stopThread()
    }

    fun wakeupSendThread(task: SendBleDataThread.BleSendTask?) {
        if (sendDataThread != null && task != null && gatt == task.bleGatt) {
            sendDataThread?.wakeupSendThread(task)
        }
    }

    fun addSendTask(serviceUUID: UUID, characteristicUUID: UUID, data: ByteArray, callback: OnWriteDataCallback?): Boolean {
        val thread = sendDataThread
        return if (thread != null && thread.isRunning()) {
            thread.addSendTask(gatt, serviceUUID, characteristicUUID, data, callback)
        } else {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDataToDeviceByBle(gatt: BluetoothGatt, serviceUUID: UUID, characteristicUUID: UUID, data: ByteArray): Boolean {
        if (data.isEmpty() || !AppUtil.checkHasConnectPermission(context)) {
            JL_Log.d(tag, "writeDataByBle : param is invalid.")
            return false
        }
        val gattService = gatt.getService(serviceUUID)
        if (gattService == null) {
            JL_Log.d(tag, "writeDataByBle : service is null.")
            return false
        }
        val gattCharacteristic = gattService.getCharacteristic(characteristicUUID)
        if (gattCharacteristic == null) {
            JL_Log.d(tag, "writeDataByBle : characteristic is null")
            return false
        }
        var ret = false
        try {
            gattCharacteristic.setValue(data)
            ret = gatt.writeCharacteristic(gattCharacteristic)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        JL_Log.d(tag, "writeDataByBle : send ret : $ret, data = " + CHexConver.byte2HexStr(data))
        return ret
    }

    override fun toString(): String =
        "BleDevice{context=$context, gatt=$gatt, mtu=$mtu, connectedTime=$connectedTime, sendDataThread=$sendDataThread}"
}
