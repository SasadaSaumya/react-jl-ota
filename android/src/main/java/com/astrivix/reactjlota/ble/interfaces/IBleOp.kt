package com.astrivix.reactjlota.ble.interfaces

import android.bluetooth.BluetoothGatt
import java.util.UUID

interface IBleOp {

    fun getBleMtu(): Int

    fun writeDataByBle(gatt: BluetoothGatt, serviceUUID: UUID, characteristicUUID: UUID, data: ByteArray): Boolean
}
