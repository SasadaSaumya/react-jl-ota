package com.astrivix.reactjlota.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import android.os.Handler
import android.os.Looper
import com.astrivix.reactjlota.ble.interfaces.BleEventCallback
import com.astrivix.reactjlota.ble.model.BleScanInfo
import java.util.UUID

/** Fans BLE events out to every registered [BleEventCallback], dispatched on the main thread. */
class BleEventCallbackManager : BleEventCallback() {
    private val callbacks = ArrayList<BleEventCallback>()
    private val handler = Handler(Looper.getMainLooper())

    fun registerBleEventCallback(callback: BleEventCallback?) {
        if (callback != null && !callbacks.contains(callback)) {
            callbacks.add(callback)
        }
    }

    fun unregisterBleEventCallback(callback: BleEventCallback?) {
        if (callback != null) {
            callbacks.remove(callback)
        }
    }

    fun release() {
        callbacks.clear()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onAdapterChange(bEnabled: Boolean) {
        dispatch { it.onAdapterChange(bEnabled) }
    }

    override fun onDiscoveryBleChange(bStart: Boolean) {
        dispatch { it.onDiscoveryBleChange(bStart) }
    }

    override fun onDiscoveryBle(device: BluetoothDevice, bleScanInfo: BleScanInfo) {
        dispatch { it.onDiscoveryBle(device, bleScanInfo) }
    }

    override fun onBleConnection(device: BluetoothDevice, status: Int) {
        dispatch { it.onBleConnection(device, status) }
    }

    override fun onBleServiceDiscovery(device: BluetoothDevice, status: Int, services: List<BluetoothGattService>?) {
        dispatch { it.onBleServiceDiscovery(device, status, services) }
    }

    override fun onBleNotificationStatus(device: BluetoothDevice, serviceUuid: UUID?, characteristicUuid: UUID?, status: Int) {
        dispatch { it.onBleNotificationStatus(device, serviceUuid, characteristicUuid, status) }
    }

    override fun onBleDataBlockChanged(device: BluetoothDevice, block: Int, status: Int) {
        dispatch { it.onBleDataBlockChanged(device, block, status) }
    }

    override fun onBleDataNotification(device: BluetoothDevice, serviceUuid: UUID?, characteristicsUuid: UUID?, data: ByteArray?) {
        dispatch { it.onBleDataNotification(device, serviceUuid, characteristicsUuid, data) }
    }

    override fun onBleWriteStatus(device: BluetoothDevice, serviceUuid: UUID?, characteristicsUuid: UUID?, data: ByteArray?, status: Int) {
        dispatch { it.onBleWriteStatus(device, serviceUuid, characteristicsUuid, data, status) }
    }

    override fun onConnectionUpdated(device: BluetoothDevice, interval: Int, latency: Int, timeout: Int, status: Int) {
        dispatch { it.onConnectionUpdated(device, interval, latency, timeout, status) }
    }

    private fun dispatch(action: (BleEventCallback) -> Unit) {
        val runnable = Runnable {
            if (callbacks.isNotEmpty()) {
                for (callback in ArrayList(callbacks)) {
                    action(callback)
                }
            }
        }
        if (Thread.currentThread() === Looper.getMainLooper().thread) {
            runnable.run()
        } else {
            handler.post(runnable)
        }
    }
}
