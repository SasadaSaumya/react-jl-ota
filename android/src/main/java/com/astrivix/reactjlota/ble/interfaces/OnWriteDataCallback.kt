package com.astrivix.reactjlota.ble.interfaces

import android.bluetooth.BluetoothDevice
import java.util.UUID

fun interface OnWriteDataCallback {

    /** Callback with the BLE write outcome for [data] sent to [characteristicUUID]. */
    fun onBleResult(device: BluetoothDevice, serviceUUID: UUID, characteristicUUID: UUID, result: Boolean, data: ByteArray)
}
