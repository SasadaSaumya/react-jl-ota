package com.astrivix.reactjlota.ble.interfaces

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import com.astrivix.reactjlota.ble.model.BleScanInfo
import java.util.UUID

/**
 * BLE event callback surface. Mirrors the reference `IBleEventCallback` used by the
 * proven JieLi native-BLE integration (`expo-jl-ota`): adapter on/off, scan
 * lifecycle, discovered devices, connection state, service discovery, notification
 * enable status, MTU changes, incoming notifications, write status, and connection
 * parameter updates.
 */
interface IBleEventCallback {

    fun onAdapterChange(bEnabled: Boolean)

    fun onDiscoveryBleChange(bStart: Boolean)

    fun onDiscoveryBle(device: BluetoothDevice, bleScanInfo: BleScanInfo)

    fun onBleConnection(device: BluetoothDevice, status: Int)

    fun onBleServiceDiscovery(device: BluetoothDevice, status: Int, services: List<BluetoothGattService>?)

    fun onBleNotificationStatus(device: BluetoothDevice, serviceUuid: UUID?, characteristicUuid: UUID?, status: Int)

    fun onBleDataBlockChanged(device: BluetoothDevice, block: Int, status: Int)

    fun onBleDataNotification(device: BluetoothDevice, serviceUuid: UUID?, characteristicsUuid: UUID?, data: ByteArray?)

    fun onBleWriteStatus(device: BluetoothDevice, serviceUuid: UUID?, characteristicsUuid: UUID?, data: ByteArray?, status: Int)

    fun onConnectionUpdated(device: BluetoothDevice, interval: Int, latency: Int, timeout: Int, status: Int)
}
