package com.astrivix.reactjlota.ble.interfaces

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import com.astrivix.reactjlota.ble.model.BleScanInfo
import java.util.UUID

/** No-op default implementation of [IBleEventCallback] so listeners only override what they need. */
abstract class BleEventCallback : IBleEventCallback {

    override fun onAdapterChange(bEnabled: Boolean) {}

    override fun onDiscoveryBleChange(bStart: Boolean) {}

    override fun onDiscoveryBle(device: BluetoothDevice, bleScanInfo: BleScanInfo) {}

    override fun onBleConnection(device: BluetoothDevice, status: Int) {}

    override fun onBleServiceDiscovery(device: BluetoothDevice, status: Int, services: List<BluetoothGattService>?) {}

    override fun onBleNotificationStatus(device: BluetoothDevice, serviceUuid: UUID?, characteristicUuid: UUID?, status: Int) {}

    override fun onBleDataBlockChanged(device: BluetoothDevice, block: Int, status: Int) {}

    override fun onBleDataNotification(device: BluetoothDevice, serviceUuid: UUID?, characteristicsUuid: UUID?, data: ByteArray?) {}

    override fun onBleWriteStatus(device: BluetoothDevice, serviceUuid: UUID?, characteristicsUuid: UUID?, data: ByteArray?, status: Int) {}

    override fun onConnectionUpdated(device: BluetoothDevice, interval: Int, latency: Int, timeout: Int, status: Int) {}
}
