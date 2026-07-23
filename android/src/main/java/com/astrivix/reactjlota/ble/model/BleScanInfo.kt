package com.astrivix.reactjlota.ble.model

import android.os.Parcel
import android.os.Parcelable
import com.jieli.jl_bt_ota.util.CHexConver

/** BLE scan record: raw advertisement bytes, RSSI, and whether the device is connectable. */
class BleScanInfo() : Parcelable {
    var rawData: ByteArray? = null
        private set
    var rssi: Int = 0
        private set
    var isEnableConnect: Boolean = true
        private set

    private constructor(parcel: Parcel) : this() {
        rawData = parcel.createByteArray()
        rssi = parcel.readInt()
        isEnableConnect = parcel.readByte().toInt() != 0
    }

    fun setRawData(rawData: ByteArray?): BleScanInfo {
        this.rawData = rawData
        return this
    }

    fun setRssi(rssi: Int): BleScanInfo {
        this.rssi = rssi
        return this
    }

    fun setEnableConnect(enableConnect: Boolean): BleScanInfo {
        isEnableConnect = enableConnect
        return this
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeByteArray(rawData)
        dest.writeInt(rssi)
        dest.writeByte((if (isEnableConnect) 1 else 0).toByte())
    }

    override fun toString(): String =
        "BleScanInfo{rawData=${CHexConver.byte2HexStr(rawData)}, rssi=$rssi, isEnableConnect=$isEnableConnect}"

    companion object CREATOR : Parcelable.Creator<BleScanInfo> {
        override fun createFromParcel(parcel: Parcel): BleScanInfo = BleScanInfo(parcel)
        override fun newArray(size: Int): Array<BleScanInfo?> = arrayOfNulls(size)
    }
}
