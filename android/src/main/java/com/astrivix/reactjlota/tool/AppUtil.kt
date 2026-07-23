package com.astrivix.reactjlota.tool

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.TextUtils
import androidx.core.app.ActivityCompat
import com.jieli.jl_bt_ota.constant.StateCode
import com.jieli.jl_bt_ota.util.BluetoothUtil
import com.jieli.jl_bt_ota.util.CHexConver
import com.jieli.jl_bt_ota.util.JL_Log
import java.util.Locale
import java.util.UUID

object AppUtil {

    fun isHasLocationPermission(context: Context): Boolean =
        isHasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun isHasStoragePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return isHasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return isHasPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) &&
            isHasPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun checkHasConnectPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            return isHasPermission(context, "android.permission.BLUETOOTH_CONNECT")
        }
        return true
    }

    fun checkHasScanPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            return isHasPermission(context, "android.permission.BLUETOOTH_SCAN")
        }
        return true
    }

    fun isHasPermission(context: Context?, permission: String): Boolean =
        context != null && ActivityCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun enableBluetooth(context: Context): Boolean {
        if (!checkHasConnectPermission(context)) return false
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        var ret = bluetoothAdapter.isEnabled
        if (!ret) {
            ret = bluetoothAdapter.enable()
        }
        return ret
    }

    /** Clear cached GATT services for [bluetoothGatt]. Use after disconnect, before releasing resources. */
    @SuppressLint("MissingPermission")
    fun refreshBleDeviceCache(context: Context, bluetoothGatt: BluetoothGatt?): Boolean {
        if (bluetoothGatt == null || !checkHasConnectPermission(context)) return false
        return try {
            val refreshMethod = bluetoothGatt.javaClass.getMethod("refresh")
            refreshMethod.invoke(bluetoothGatt) == true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun deviceHasProfile(context: Context, device: BluetoothDevice?, uuid: UUID?): Boolean {
        if (!BluetoothUtil.isBluetoothEnable() || device == null || uuid == null || TextUtils.isEmpty(uuid.toString()) ||
            !checkHasConnectPermission(context)
        ) {
            return false
        }
        val uuids = device.uuids
        if (uuids == null || uuids.isEmpty()) return false
        for (uid in uuids) {
            if (uuid.toString().lowercase(Locale.getDefault()).equals(uid.toString(), ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    fun getDeviceName(context: Context, device: BluetoothDevice?): String {
        if (device == null || !checkHasConnectPermission(context)) return "N/A"
        val name = device.name
        return if (TextUtils.isEmpty(name)) "N/A" else name
    }

    @SuppressLint("MissingPermission")
    fun getDeviceType(context: Context, device: BluetoothDevice?): Int {
        if (device == null || !checkHasConnectPermission(context)) return BluetoothDevice.DEVICE_TYPE_UNKNOWN
        return device.type
    }

    @SuppressLint("MissingPermission")
    fun printBleGattServices(context: Context, device: BluetoothDevice?, gatt: BluetoothGatt?, status: Int) {
        if (device == null || gatt == null || !checkHasConnectPermission(context)) return
        val tag = "ble"
        if (!JL_Log.isIsLog()) return
        JL_Log.d(
            tag,
            String.format(
                Locale.getDefault(),
                "[[============================Bluetooth[%s], Discovery Services status[%d]=================================]]\n",
                BluetoothUtil.printBtDeviceInfo(context, device), status
            )
        )
        val services: List<BluetoothGattService>? = gatt.services
        if (services != null) {
            JL_Log.d(tag, "[[======Service Size:" + services.size + "======================\n")
            for (service in services) {
                JL_Log.d(tag, "[[======Service:" + service.uuid + "======================\n")
                val characteristics: List<BluetoothGattCharacteristic>? = service.characteristics
                if (characteristics != null) {
                    JL_Log.d(tag, "[[[[=============characteristics Size:" + characteristics.size + "======================\n")
                    for (characteristic in characteristics) {
                        JL_Log.d(
                            tag,
                            "[[[[=============characteristic:" + characteristic.uuid +
                                ",write type : " + characteristic.writeType + "======================\n"
                        )
                        val descriptors: List<BluetoothGattDescriptor>? = characteristic.descriptors
                        if (descriptors != null) {
                            JL_Log.d(tag, "[[[[[[=============descriptors Size:" + descriptors.size + "======================\n")
                            for (descriptor in descriptors) {
                                JL_Log.d(
                                    tag,
                                    "[[[[[[=============descriptor:" + descriptor.uuid + ",permission:" + descriptor.permissions +
                                        "\nvalue : " + CHexConver.byte2HexStr(descriptor.value) + "======================\n"
                                )
                            }
                        }
                    }
                }
            }
        }
        JL_Log.d(tag, "[[============================Bluetooth[" + BluetoothUtil.printBtDeviceInfo(context, device) + "] Services show End=================================]]\n")
    }

    /** Convert an Android [BluetoothProfile] connection state into a JieLi [StateCode]. */
    fun changeConnectStatus(status: Int): Int = when (status) {
        BluetoothProfile.STATE_CONNECTED -> StateCode.CONNECTION_OK
        BluetoothProfile.STATE_CONNECTING -> StateCode.CONNECTION_CONNECTING
        else -> StateCode.CONNECTION_DISCONNECT
    }
}
