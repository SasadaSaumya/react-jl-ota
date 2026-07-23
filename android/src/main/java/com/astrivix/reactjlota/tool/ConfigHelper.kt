package com.astrivix.reactjlota.tool

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.IntRange

/** SharedPreferences-backed tunables for the native BLE engine. Same defaults as the proven reference. */
class ConfigHelper private constructor(context: Context) {

    private val preferences: SharedPreferences =
        context.getSharedPreferences("ota_config_data", Context.MODE_PRIVATE)

    companion object {
        @Volatile
        private var instance: ConfigHelper? = null
        private var appContext: Context? = null

        private const val KEY_COMMUNICATION_WAY = "communication_way"
        private const val KEY_IS_USE_DEVICE_AUTH = "is_use_device_auth"
        private const val KEY_IS_HID_DEVICE = "is_hid_device"
        private const val KEY_USE_CUSTOM_RECONNECT_WAY = "use_custom_reconnect_way"
        private const val KEY_BLE_MTU_VALUE = "ble_mtu_value"
        private const val KEY_FAULT_TOLERANT = "fault_tolerant"
        private const val KEY_FAULT_TOLERANT_COUNT = "fault_tolerant_count"
        private const val KEY_SCAN_FILTER_STRING = "scan_filter_string"
        private const val KEY_DEVELOP_MODE = "develop_mode"
        private const val KEY_BROADCAST_BOX = "broadcast_box_switch"

        fun initialize(context: Context) {
            if (appContext == null) {
                appContext = context.applicationContext
            }
        }

        fun getInstance(): ConfigHelper {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val ctx = appContext
                        ?: throw IllegalStateException("ConfigHelper.initialize(context) must be called first")
                    ConfigHelper(ctx).also { instance = it }
                }
            }
        }
    }

    fun isBleWay(): Boolean =
        preferences.getInt(KEY_COMMUNICATION_WAY, OtaConstant.CURRENT_PROTOCOL) == OtaConstant.PROTOCOL_BLE

    fun setBleWay(isBle: Boolean) {
        val way = if (isBle) OtaConstant.PROTOCOL_BLE else OtaConstant.PROTOCOL_SPP
        preferences.edit().putInt(KEY_COMMUNICATION_WAY, way).apply()
    }

    fun isUseDeviceAuth(): Boolean =
        preferences.getBoolean(KEY_IS_USE_DEVICE_AUTH, OtaConstant.IS_NEED_DEVICE_AUTH)

    fun setUseDeviceAuth(isAuth: Boolean) {
        preferences.edit().putBoolean(KEY_IS_USE_DEVICE_AUTH, isAuth).apply()
    }

    fun isHidDevice(): Boolean =
        preferences.getBoolean(KEY_IS_HID_DEVICE, OtaConstant.HID_DEVICE_WAY)

    fun setHidDevice(isHid: Boolean) {
        preferences.edit().putBoolean(KEY_IS_HID_DEVICE, isHid).apply()
    }

    fun isUseCustomReConnectWay(): Boolean =
        preferences.getBoolean(KEY_USE_CUSTOM_RECONNECT_WAY, OtaConstant.NEED_CUSTOM_RECONNECT_WAY)

    fun setUseCustomReConnectWay(isCustom: Boolean) {
        preferences.edit().putBoolean(KEY_USE_CUSTOM_RECONNECT_WAY, isCustom).apply()
    }

    fun getBleRequestMtu(): Int = preferences.getInt(KEY_BLE_MTU_VALUE, 509)

    fun setBleRequestMtu(@IntRange(from = 20, to = 509) mtu: Int) {
        preferences.edit().putInt(KEY_BLE_MTU_VALUE, mtu).apply()
    }

    fun isFaultTolerant(): Boolean =
        preferences.getBoolean(KEY_FAULT_TOLERANT, OtaConstant.AUTO_FAULT_TOLERANT)

    fun setFaultTolerant(isFaultTolerant: Boolean) {
        preferences.edit().putBoolean(KEY_FAULT_TOLERANT, isFaultTolerant).apply()
    }

    fun getFaultTolerantCount(): Int =
        preferences.getInt(KEY_FAULT_TOLERANT_COUNT, OtaConstant.AUTO_FAULT_TOLERANT_COUNT)

    fun setFaultTolerantCount(count: Int) {
        if (!isFaultTolerant()) return
        preferences.edit().putInt(KEY_FAULT_TOLERANT_COUNT, count).apply()
    }

    fun getScanFilter(): String = preferences.getString(KEY_SCAN_FILTER_STRING, "") ?: ""

    fun setScanFilter(scanFilter: String) {
        preferences.edit().putString(KEY_SCAN_FILTER_STRING, scanFilter).apply()
    }

    fun isDevelopMode(): Boolean = preferences.getBoolean(KEY_DEVELOP_MODE, false)

    fun setDevelopMode(developMode: Boolean) {
        preferences.edit().putBoolean(KEY_DEVELOP_MODE, developMode).apply()
    }

    fun isEnableBroadcastBox(): Boolean = preferences.getBoolean(KEY_BROADCAST_BOX, false)

    fun enableBroadcastBox(enable: Boolean) {
        preferences.edit().putBoolean(KEY_BROADCAST_BOX, enable).apply()
    }
}
