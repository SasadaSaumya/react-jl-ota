package com.astrivix.reactjlota.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import com.astrivix.reactjlota.ble.interfaces.BleEventCallback
import com.astrivix.reactjlota.ble.model.BleScanInfo
import com.jieli.jl_bt_ota.constant.JL_Constant
import com.jieli.jl_bt_ota.model.BleScanMessage
import com.jieli.jl_bt_ota.tool.DeviceReConnectManager
import com.jieli.jl_bt_ota.util.BluetoothUtil
import com.jieli.jl_bt_ota.util.JL_Log
import com.jieli.jl_bt_ota.util.ParseDataUtil
import java.util.Objects

/**
 * Dual-bank reconnect state machine: when the OTA SDK reports a device rebooting
 * into a new address (or the same address after a firmware bank switch), this scans
 * for and reconnects to it without JS involvement. Ported from the proven
 * `expo-jl-ota` reference — this is the piece the previous JS-bridge design in
 * `react-jl-ota` punted to the host app.
 */
class ReConnectHelper(private val mContext: Context, private val mBtManager: BleManager) {

    companion object {
        private val TAG = ReConnectHelper::class.java.simpleName
        private val RECONNECT_TIMEOUT = DeviceReConnectManager.RECONNECT_TIMEOUT.toInt()
        private const val SCAN_TIMEOUT = 20 * 1000L
        private const val FAILED_DELAY = 3 * 1000L
        private const val MSG_RECONNECT_TIMEOUT = 0x01
        private const val MSG_PROCESS_TASK = 0x02
    }

    private val mParams = ArrayList<ReconnectParam>()
    private val mBleAdvCache = HashMap<String, BleScanMessage>()

    private val mUIHandler = Handler(Looper.getMainLooper(), Handler.Callback { msg ->
        when (msg.what) {
            MSG_RECONNECT_TIMEOUT -> {
                stopBtScan()
                mParams.clear()
            }
            MSG_PROCESS_TASK -> processReconnectTask()
            else -> {
                val address = msg.obj as? String
                if (address != null) removeParam(address)
            }
        }
        true
    })

    fun release() {
        mParams.clear()
        mBleAdvCache.clear()
        mUIHandler.removeCallbacksAndMessages(null)
        mBtManager.unregisterBleEventCallback(bleEventCallback)
    }

    fun isReconnecting(): Boolean = mUIHandler.hasMessages(MSG_RECONNECT_TIMEOUT)

    fun isMatchAddress(srcAddress: String, checkAddress: String): Boolean {
        val param = getCacheParam(srcAddress)
        return param != null && BluetoothAdapter.checkBluetoothAddress(checkAddress) &&
            (checkAddress == param.deviceAddress || checkAddress == param.connectAddress)
    }

    fun putParam(param: ReconnectParam?): Boolean {
        if (param == null) return false
        if (!mParams.contains(param)) {
            if (mParams.add(param)) {
                mUIHandler.sendEmptyMessageDelayed(mParams.hashCode(), RECONNECT_TIMEOUT.toLong())
                if (!isReconnecting()) {
                    mUIHandler.sendMessageDelayed(
                        mUIHandler.obtainMessage(MSG_RECONNECT_TIMEOUT, param.deviceAddress),
                        (RECONNECT_TIMEOUT + 10 * 1000).toLong()
                    )
                    mUIHandler.sendEmptyMessage(MSG_PROCESS_TASK)
                }
                return true
            }
        } else {
            return true
        }
        return false
    }

    private fun stopBtScan() {
        mBtManager.stopLeScan()
    }

    private fun getCacheParam(address: String): ReconnectParam? {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return null
        val advMsg = mBleAdvCache[address]
        return ArrayList(mParams).firstOrNull {
            address == it.deviceAddress || (advMsg != null && it.deviceAddress == advMsg.oldBleAddress)
        }
    }

    private fun removeParam(address: String) {
        val param = getCacheParam(address) ?: return
        if (mParams.remove(param)) {
            mUIHandler.removeMessages(param.hashCode())
            if (mParams.isEmpty()) {
                mUIHandler.removeMessages(MSG_RECONNECT_TIMEOUT)
                return
            }
        }
        mUIHandler.sendEmptyMessage(MSG_PROCESS_TASK)
    }

    private fun processReconnectTask() {
        if (mBtManager.isBleScanning()) {
            mUIHandler.sendEmptyMessageDelayed(MSG_PROCESS_TASK, FAILED_DELAY)
            return
        }
        val connectedDevice = systemConnectedDevice()
        if (connectedDevice != null) {
            getCacheParam(connectedDevice.address)?.connectAddress = connectedDevice.address
            mBtManager.connectBleDevice(connectedDevice)
            return
        }
        if (!mBtManager.startLeScan(SCAN_TIMEOUT)) {
            JL_Log.i(TAG, "processReconnectTask : start Le scan failed.")
            mUIHandler.sendEmptyMessageDelayed(MSG_PROCESS_TASK, FAILED_DELAY)
        }
    }

    private fun systemConnectedDevice(): BluetoothDevice? {
        val list = BluetoothUtil.getSystemConnectedBtDeviceList(mContext) ?: return null
        return list.firstOrNull { isReconnectDevice(it, null) }
    }

    private fun isReconnectDevice(device: BluetoothDevice?, message: BleScanMessage?): Boolean {
        if (device == null || mParams.isEmpty()) return false
        return ArrayList(mParams).any { param ->
            if (param.isUseNewADV && message != null && message.isOTA()) {
                param.deviceAddress == message.oldBleAddress
            } else {
                param.deviceAddress == device.address
            }
        }
    }

    private val bleEventCallback = object : BleEventCallback() {
        override fun onAdapterChange(bEnabled: Boolean) {
            if (!isReconnecting()) return
            if (bEnabled) {
                JL_Log.i(TAG, "onAdapterChange : bluetooth is on, try to start le scan.")
                mUIHandler.sendEmptyMessage(MSG_PROCESS_TASK)
            }
        }

        override fun onDiscoveryBleChange(bStart: Boolean) {
            if (!isReconnecting()) return
            val isConnecting = mBtManager.isConnecting()
            JL_Log.i(TAG, "onDiscoveryBleChange : $bStart, isConnecting = $isConnecting")
            if (!bStart && !isConnecting) {
                mUIHandler.sendEmptyMessage(MSG_PROCESS_TASK)
            }
        }

        override fun onDiscoveryBle(device: BluetoothDevice, bleScanInfo: BleScanInfo) {
            if (!isReconnecting()) return
            val advMsg = ParseDataUtil.parseOTAFlagFilterWithBroad(bleScanInfo.rawData, JL_Constant.OTA_IDENTIFY)
            if (advMsg != null) {
                mBleAdvCache[device.address] = advMsg
                JL_Log.d(TAG, "onDiscoveryBle : put data in map.")
            }
            val isReconnect = isReconnectDevice(device, advMsg)
            JL_Log.d(TAG, "onDiscoveryBle : $device, isReconnectDevice = $isReconnect, $advMsg")
            if (isReconnect) {
                stopBtScan()
                getCacheParam(device.address)?.connectAddress = device.address
                mBtManager.connectBleDevice(device)
            }
        }

        override fun onBleConnection(device: BluetoothDevice, status: Int) {
            if (!isReconnecting()) return
            val advMsg = mBleAdvCache[device.address]
            if (!isReconnectDevice(device, advMsg)) return
            JL_Log.i(TAG, "onBleConnection : $device, status = $status, $advMsg")
            if (status == BluetoothProfile.STATE_CONNECTED) {
                JL_Log.w(TAG, "onBleConnection : removeParam >>> " + device.address)
                removeParam(device.address)
            } else if (status == BluetoothProfile.STATE_DISCONNECTED) {
                JL_Log.i(TAG, "-onConnection- resume reconnect task.")
                mUIHandler.sendEmptyMessage(MSG_PROCESS_TASK)
            }
        }
    }

    init {
        mBtManager.registerBleEventCallback(bleEventCallback)
    }

    class ReconnectParam(val deviceAddress: String, val isUseNewADV: Boolean) {
        var connectAddress: String? = null

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ReconnectParam) return false
            return isUseNewADV == other.isUseNewADV && deviceAddress == other.deviceAddress
        }

        override fun hashCode(): Int = Objects.hash(deviceAddress, isUseNewADV)

        override fun toString(): String =
            "ReconnectParam{deviceAddress='$deviceAddress', isUseNewADV=$isUseNewADV, connectAddress='$connectAddress'}"
    }
}
