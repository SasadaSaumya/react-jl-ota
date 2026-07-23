package com.astrivix.reactjlota.tool

import java.util.UUID

object OtaConstant {

    val UUID_A2DP: UUID = UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")
    val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    const val PROTOCOL_BLE = 0
    const val PROTOCOL_SPP = 1

    const val CURRENT_PROTOCOL = PROTOCOL_BLE

    const val IS_NEED_DEVICE_AUTH = true

    const val HID_DEVICE_WAY = false

    const val NEED_CUSTOM_RECONNECT_WAY = false

    const val USE_SPP_MULTIPLE_CHANNEL = false

    const val AUTO_TEST_OTA = false
    const val AUTO_TEST_COUNT = 30

    const val AUTO_FAULT_TOLERANT = false
    const val AUTO_FAULT_TOLERANT_COUNT = 1

    const val DIR_ROOT = "JieLiOTA"
    const val DIR_UPGRADE = "upgrade"
    const val DIR_LOGCAT = "logcat"

    const val SCAN_TIMEOUT = 16 * 1000L
}
