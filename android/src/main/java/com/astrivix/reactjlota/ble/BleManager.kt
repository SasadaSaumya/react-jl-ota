package com.astrivix.reactjlota.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import com.astrivix.reactjlota.ble.interfaces.BleEventCallback
import com.astrivix.reactjlota.ble.interfaces.OnWriteDataCallback
import com.astrivix.reactjlota.ble.model.BleDevice
import com.astrivix.reactjlota.ble.model.BleScanInfo
import com.astrivix.reactjlota.tool.AppUtil
import com.astrivix.reactjlota.tool.ConfigHelper
import com.jieli.jl_bt_ota.constant.BluetoothConstant
import com.jieli.jl_bt_ota.util.BluetoothUtil
import com.jieli.jl_bt_ota.util.CHexConver
import com.jieli.jl_bt_ota.util.JL_Log
import java.util.Collections
import java.util.Locale
import java.util.UUID

/**
 * Native BLE connection manager: scanning, GATT connect/disconnect, service
 * discovery, notification enable, MTU negotiation, and a serialized write queue.
 * Ported faithfully from the proven `expo-jl-ota` reference `BleManager` — this is
 * the piece that lets the JieLi OTA SDK own the Bluetooth link directly instead of
 * delegating it to JS.
 */
class BleManager(context: Context, mac: String, deviceName: String) {

    companion object {
        private val TAG = BleManager::class.java.simpleName

        @Volatile
        private var instance: BleManager? = null

        fun getInstance(context: Context?, mac: String, deviceName: String): BleManager {
            return instance ?: synchronized(BleManager::class.java) {
                instance ?: run {
                    requireNotNull(context) { "Context is required to initialize BleManager" }
                    BleManager(context.applicationContext, mac, deviceName).also {
                        instance = it
                        JL_Log.w(TAG, "init BleManager.. $it")
                    }
                }
            }
        }

        val BLE_UUID_SERVICE: UUID = BluetoothConstant.UUID_SERVICE
        val BLE_UUID_WRITE: UUID = BluetoothConstant.UUID_WRITE
        val BLE_UUID_NOTIFICATION: UUID = BluetoothConstant.UUID_NOTIFICATION
        val BLE_UUID_NOTIFICATION_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        /** Send timeout — 8 s. */
        const val SEND_DATA_MAX_TIMEOUT = 8000
        private const val SCAN_BLE_TIMEOUT = 12 * 1000L
        private const val CONNECT_BLE_TIMEOUT = 40 * 1000L
        private const val CALLBACK_TIMEOUT = 6000L

        private const val MSG_SCAN_BLE_TIMEOUT = 0x1010
        private const val MSG_CONNECT_BLE_TIMEOUT = 0x1011
        private const val MSG_SCAN_HID_DEVICE = 0x1012
        private const val MSG_NOTIFY_BLE_TIMEOUT = 0x1013
        private const val MSG_CHANGE_BLE_MTU_TIMEOUT = 0x1014
        private const val MSG_BLE_DISCOVER_SERVICES_CALLBACK_TIMEOUT = 0x1015

        private const val MAX_RETRY_CONNECT_COUNT = 1
        private const val MIN_CONNECT_TIME = 5 * 1000L

        @SuppressLint("MissingPermission")
        fun getConnectedBleDeviceList(context: Context): List<BluetoothDevice>? {
            if (!AppUtil.checkHasConnectPermission(context)) return null
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
        }
    }

    private val mContext: Context = context
    private val configHelper = ConfigHelper.getInstance()

    var mac: String = mac
    var deviceName: String = deviceName

    private var mAdapterReceiver: BaseBtAdapterReceiver? = null
    private val mBluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var mBluetoothLeScanner: BluetoothLeScanner? = null

    private val mReConnectHelper: ReConnectHelper = ReConnectHelper(context, this)

    @Volatile
    private var mConnectingBtDevice: BluetoothDevice? = null

    @Volatile
    private var mUsingDevice: BluetoothDevice? = null

    private val mConnectedGattMap = HashMap<String, BleDevice>()
    private val mDiscoveredBleDevices = ArrayList<BluetoothDevice>()
    private val mCallbackManager = BleEventCallbackManager()

    @Volatile
    private var isBleScanning = false
    var isConnected = false
    private var mNotifyCharacteristicRunnable: NotifyCharacteristicRunnable? = null

    private var mRetryConnectCount = 0
    private var startConnectTime = 0L

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mBluetoothAdapter != null) {
            mBluetoothLeScanner = mBluetoothAdapter.bluetoothLeScanner
            Log.e(TAG, "mBluetoothLeScanner==$mBluetoothLeScanner")
        }
        registerReceiver()
    }

    private val mHandler: Handler = Handler(Looper.getMainLooper(), Handler.Callback { msg ->
        when (msg.what) {
            MSG_SCAN_BLE_TIMEOUT -> {
                if (isBleScanning) stopLeScan()
            }
            MSG_CONNECT_BLE_TIMEOUT -> {
                val device = msg.obj as? BluetoothDevice
                if (device != null) {
                    val bleDevice = getConnectedBle(device)
                    if (bleDevice == null) {
                        handleBleConnection(device, BluetoothProfile.STATE_DISCONNECTED)
                    }
                    setConnectingBtDevice(null)
                }
            }
            MSG_SCAN_HID_DEVICE -> {
                val lists = BluetoothUtil.getSystemConnectedBtDeviceList(mContext)
                if (lists != null && AppUtil.checkHasConnectPermission(mContext)) {
                    for (device in lists) {
                        if (device.type != BluetoothDevice.DEVICE_TYPE_CLASSIC && device.bondState == BluetoothDevice.BOND_BONDED) {
                            handleDiscoveryBle(device, null)
                        }
                    }
                }
                mHandler.sendEmptyMessageDelayed(MSG_SCAN_HID_DEVICE, 1000)
            }
            MSG_NOTIFY_BLE_TIMEOUT -> {
                val device = msg.obj as? BluetoothDevice
                if (device != null) disconnectBleDevice(device)
            }
            MSG_CHANGE_BLE_MTU_TIMEOUT -> {
                val device = msg.obj as BluetoothDevice
                val bleDevice = getConnectedBle(device)
                JL_Log.i(TAG, "-MSG_CHANGE_BLE_MTU_TIMEOUT- request mtu timeout, device : " + printDeviceInfo(device) + ", " + bleDevice)
                if (bleDevice != null) {
                    handleBleConnectedEvent(device)
                } else {
                    handleBleConnection(device, BluetoothProfile.STATE_DISCONNECTED)
                }
            }
            MSG_BLE_DISCOVER_SERVICES_CALLBACK_TIMEOUT -> {
                val connectedBleDev = msg.obj as? BluetoothDevice
                if (connectedBleDev != null && BluetoothUtil.deviceEquals(connectedBleDev, mUsingDevice)) {
                    var isNeedDisconnect = true
                    val bleDevice = getConnectedBle(connectedBleDev)
                    if (bleDevice != null) {
                        val services = bleDevice.gatt.services
                        if (services != null && services.isNotEmpty()) {
                            mBluetoothGattCallback.onServicesDiscovered(bleDevice.gatt, BluetoothGatt.GATT_SUCCESS)
                            isNeedDisconnect = false
                        }
                    }
                    if (isNeedDisconnect) {
                        JL_Log.d(TAG, "discover services timeout.")
                        disconnectBleDevice(connectedBleDev)
                        reconnectDevice(connectedBleDev.address, false)
                    }
                }
            }
        }
        false
    })

    fun reconnectDevice(address: String, isUseAdv: Boolean) {
        JL_Log.d(TAG, "reconnectDevice : address = $address, isUseAdv = $isUseAdv")
        val ret = mReConnectHelper.putParam(ReConnectHelper.ReconnectParam(address, isUseAdv))
        JL_Log.d(TAG, "reconnectDevice : ret = $ret")
    }

    fun destroy() {
        JL_Log.w(TAG, ">>>>>>>>>>>>>>destroy >>>>>>>>>>>>>>> ")
        unregisterReceiver()
        clearConnectedBleDevices()
        if (isBleScanning()) stopLeScan()
        isBleScanning(false)
        mDiscoveredBleDevices.clear()
        mReConnectHelper.release()
        mCallbackManager.release()
        mHandler.removeCallbacksAndMessages(null)
    }

    fun registerBleEventCallback(callback: BleEventCallback) {
        mCallbackManager.registerBleEventCallback(callback)
    }

    fun unregisterBleEventCallback(callback: BleEventCallback) {
        mCallbackManager.unregisterBleEventCallback(callback)
    }

    fun isBluetoothEnable(): Boolean = mBluetoothAdapter != null && mBluetoothAdapter.isEnabled

    fun isBleScanning(): Boolean = isBleScanning

    @SuppressLint("MissingPermission")
    private fun getConnectedBleDevices(): List<BluetoothDevice>? {
        val bluetoothManager = mContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.getConnectedDevices(BluetoothProfile.GATT)
    }

    @SuppressLint("MissingPermission")
    fun startLeScan(timeoutIn: Long): Boolean {
        var timeout = timeoutIn
        val tag = "startLeScan"
        if (mBluetoothAdapter == null || !AppUtil.checkHasScanPermission(mContext)) return false
        if (!isBluetoothEnable() || !AppUtil.isHasLocationPermission(mContext)) return false
        if (timeout <= 0) timeout = SCAN_BLE_TIMEOUT
        if (isBleScanning) {
            JL_Log.i(tag, "scanning ble .....")
            mBluetoothLeScanner?.flushPendingScanResults(mScanCallback)
            mDiscoveredBleDevices.clear()
            mHandler.removeMessages(MSG_SCAN_BLE_TIMEOUT)
            mHandler.sendEmptyMessageDelayed(MSG_SCAN_BLE_TIMEOUT, timeout)
            syncSystemBleDevice()
            return true
        }
        val connectedDevices = getConnectedBleDevices()
        if (!connectedDevices.isNullOrEmpty()) {
            for (device in connectedDevices) {
                filterDevice(device, 0, null, true)
            }
        }
        val ret: Boolean
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mBluetoothLeScanner != null) {
            val scanMode = ScanSettings.SCAN_MODE_BALANCED
            val scanSettings = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ScanSettings.Builder().setScanMode(scanMode).setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE).build()
            } else {
                ScanSettings.Builder().setScanMode(scanMode).build()
            }
            mBluetoothLeScanner?.startScan(null, scanSettings, mScanCallback)
            ret = true
        } else {
            ret = mBluetoothAdapter.startLeScan(mLeScanCallback)
            mHandler.removeMessages(MSG_SCAN_BLE_TIMEOUT)
            mHandler.sendEmptyMessageDelayed(MSG_SCAN_BLE_TIMEOUT, timeout)
        }
        JL_Log.i(tag, "startLeScan : $ret, timeout = $timeout")
        isBleScanning(ret)
        if (ret) {
            mDiscoveredBleDevices.clear()
            mHandler.removeMessages(MSG_SCAN_BLE_TIMEOUT)
            mHandler.sendEmptyMessageDelayed(MSG_SCAN_BLE_TIMEOUT, timeout)
            syncSystemBleDevice()
        }
        return ret
    }

    @SuppressLint("MissingPermission")
    fun stopLeScan() {
        if (mBluetoothAdapter == null || !isBluetoothEnable() || !AppUtil.checkHasScanPermission(mContext)) return
        if (!isBleScanning()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && mBluetoothLeScanner != null) {
                mBluetoothLeScanner?.stopScan(mScanCallback)
            } else {
                mBluetoothAdapter.stopLeScan(mLeScanCallback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mHandler.removeMessages(MSG_SCAN_BLE_TIMEOUT)
        mHandler.removeMessages(MSG_SCAN_HID_DEVICE)
        isBleScanning(false)
    }

    fun getConnectedBtDevice(): BluetoothDevice? = mUsingDevice

    fun getConnectedBtGatt(device: BluetoothDevice): BluetoothGatt? = getConnectedBle(device)?.gatt

    fun getConnectedBLEDevice(address: String): BluetoothDevice? {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return null
        return getConnectedDeviceList().firstOrNull { it.address == address }
    }

    /** Connected devices, most-recently-connected first. */
    fun getConnectedDeviceList(): List<BluetoothDevice> {
        if (mConnectedGattMap.isEmpty()) return emptyList()
        return getSortList().mapNotNull { it.gatt.device }
    }

    fun getBleMtu(device: BluetoothDevice): Int = getConnectedBle(device)?.getMtu() ?: 0

    fun isConnecting(): Boolean = mConnectingBtDevice != null

    fun isConnectingDevice(device: BluetoothDevice?): Boolean = BluetoothUtil.deviceEquals(mConnectingBtDevice, device)

    fun isConnectedDevice(device: BluetoothDevice?): Boolean {
        if (device == null) return false
        return isConnectedDevice(device.address)
    }

    fun isConnectedDevice(address: String): Boolean {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return false
        return getConnectedDeviceList().any { it.address == address }
    }

    @SuppressLint("MissingPermission")
    fun connectBleDevice(device: BluetoothDevice?): Boolean {
        if (device == null || !AppUtil.checkHasConnectPermission(mContext)) return false
        val connecting = mConnectingBtDevice
        if (connecting != null) {
            JL_Log.e(TAG, "BleDevice is connecting, please wait.")
            return isConnectingDevice(device)
        }
        if (isBleScanning()) stopLeScan()
        var gatt: BluetoothGatt? = null
        try {
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(mContext, false, mBluetoothGattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(mContext, false, mBluetoothGattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "gatt connect exception $e")
        }
        val ret = gatt != null
        if (ret) {
            setConnectingBtDevice(device)
            handleBleConnection(device, BluetoothProfile.STATE_CONNECTING)
            JL_Log.d(TAG, "connect start...." + printDeviceInfo(mConnectingBtDevice))
        }
        return ret
    }

    @SuppressLint("MissingPermission")
    fun disconnectBleDevice(device: BluetoothDevice?) {
        if (device == null || !AppUtil.checkHasConnectPermission(mContext)) return
        val bleDevice = removeConnectedBle(device)
        JL_Log.i(TAG, "disconnectBleDevice : " + printDeviceInfo(device) + ", " + bleDevice)
        if (bleDevice != null) {
            if (BluetoothUtil.isBluetoothEnable()) {
                bleDevice.gatt.disconnect()
            }
        } else {
            JL_Log.i(TAG, "disconnectBleDevice : It is not a connected device.")
        }
    }

    fun writeDataByBleAsync(device: BluetoothDevice, serviceUUID: UUID, characteristicUUID: UUID, data: ByteArray, callback: OnWriteDataCallback?) {
        addSendTask(device, serviceUUID, characteristicUUID, data, callback)
    }

    private fun isBleScanning(scanning: Boolean) {
        isBleScanning = scanning
        mCallbackManager.onDiscoveryBleChange(scanning)
        if (isBleScanning && configHelper.isHidDevice()) {
            mHandler.sendEmptyMessage(MSG_SCAN_HID_DEVICE)
        }
    }

    private fun getConnectedBle(device: BluetoothDevice?): BleDevice? {
        if (device == null) return null
        return mConnectedGattMap[device.address]
    }

    private fun putConnectedGattInMap(address: String, gatt: BluetoothGatt) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return
        val bleDevice = BleDevice(mContext, gatt)
        bleDevice.connectedTime = System.currentTimeMillis()
        mConnectedGattMap[address] = bleDevice
        if (mUsingDevice == null) {
            mUsingDevice = gatt.device
        }
    }

    private fun removeConnectedBle(device: BluetoothDevice?): BleDevice? {
        if (device == null) return null
        return removeConnectedBle(device.address)
    }

    private fun removeConnectedBle(address: String): BleDevice? {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return null
        val bleDevice = mConnectedGattMap.remove(address)
        if (bleDevice != null) {
            bleDevice.stopSendDataThread()
            if (mConnectedGattMap.isEmpty()) {
                setConnectedBtDevice(null)
            } else if (BluetoothUtil.deviceEquals(bleDevice.gatt.device, getConnectedBtDevice())) {
                val values = getSortList()
                setConnectedBtDevice(values.first().gatt.device)
            }
        }
        return bleDevice
    }

    private fun getSortList(): List<BleDevice> {
        if (mConnectedGattMap.isEmpty()) return emptyList()
        val bleDevices = ArrayList(mConnectedGattMap.values)
        Collections.sort(bleDevices) { o1, o2 -> o2.connectedTime.compareTo(o1.connectedTime) }
        return bleDevices
    }

    private fun clearConnectedBleDevices() {
        if (!AppUtil.checkHasConnectPermission(mContext)) return
        if (mConnectedGattMap.isNotEmpty()) {
            val clone = HashMap(mConnectedGattMap)
            for (bleDevice in clone.values) {
                bleDevice.gatt.disconnect()
                bleDevice.gatt.close()
            }
            mConnectedGattMap.clear()
        }
    }

    private fun setConnectingBtDevice(device: BluetoothDevice?) {
        mConnectingBtDevice = device
    }

    private fun setConnectedBtDevice(device: BluetoothDevice?) {
        mUsingDevice = device
    }

    @SuppressLint("MissingPermission")
    private fun filterDevice(device: BluetoothDevice, rssi: Int, scanRecord: ByteArray?, isBleEnableConnect: Boolean) {
        if (AppUtil.checkHasConnectPermission(mContext) && isBluetoothEnable() && !TextUtils.isEmpty(device.name) &&
            !mDiscoveredBleDevices.contains(device)
        ) {
            JL_Log.d(TAG, "notify device : " + printDeviceInfo(device))
            if (device.address.equals(mac, ignoreCase = true)) {
                Log.e("filterDevice", "target device found, connecting")
                if (connectBleDevice(device)) {
                    isConnected = false
                }
            }
            mDiscoveredBleDevices.add(device)
            handleDiscoveryBle(device, BleScanInfo().setRawData(scanRecord).setRssi(rssi).setEnableConnect(isBleEnableConnect))
        }
    }

    private fun syncSystemBleDevice() {
        val sysConnected = getConnectedBleDeviceList(mContext)
        if (!sysConnected.isNullOrEmpty()) {
            for (bleDev in sysConnected) {
                if (!BluetoothUtil.deviceEquals(bleDev, mUsingDevice) && !mDiscoveredBleDevices.contains(bleDev)) {
                    mDiscoveredBleDevices.add(bleDev)
                    handleDiscoveryBle(bleDev, BleScanInfo().setEnableConnect(true))
                }
            }
        }
    }

    private fun addSendTask(device: BluetoothDevice, serviceUUID: UUID, characteristicUUID: UUID, data: ByteArray, callback: OnWriteDataCallback?) {
        var ret = false
        val bleDevice = getConnectedBle(device)
        if (bleDevice != null) {
            ret = bleDevice.addSendTask(serviceUUID, characteristicUUID, data, callback)
        }
        if (!ret) {
            callback?.onBleResult(device, serviceUUID, characteristicUUID, false, data)
        }
    }

    private fun wakeupSendThread(gatt: BluetoothGatt, serviceUUID: UUID?, characteristicUUID: UUID?, status: Int, data: ByteArray?) {
        val bleDevice = getConnectedBle(gatt.device) ?: return
        if (serviceUUID == null || characteristicUUID == null) return
        val task = SendBleDataThread.BleSendTask(gatt, serviceUUID, characteristicUUID, data ?: ByteArray(0), null)
        task.status = status
        bleDevice.wakeupSendThread(task)
    }

    private fun handleDiscoveryBle(device: BluetoothDevice, bleScanInfo: BleScanInfo?) {
        if (bleScanInfo != null) mCallbackManager.onDiscoveryBle(device, bleScanInfo)
    }

    @SuppressLint("MissingPermission")
    private fun handleBleConnection(device: BluetoothDevice, status: Int) {
        if (status == BluetoothProfile.STATE_DISCONNECTED || status == BluetoothProfile.STATE_CONNECTED) {
            mHandler.removeMessages(MSG_NOTIFY_BLE_TIMEOUT)
            startConnectTime = 0
        } else if (status == BluetoothProfile.STATE_CONNECTING) {
            startConnectTime = System.currentTimeMillis()
        }
        JL_Log.i(TAG, "handleBleConnection >> device : $device, status : $status")
        mCallbackManager.onBleConnection(device, status)
    }

    private fun registerReceiver() {
        if (mAdapterReceiver == null) {
            val receiver = BaseBtAdapterReceiver()
            mAdapterReceiver = receiver
            val intentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            mContext.registerReceiver(receiver, intentFilter)
        }
    }

    private fun unregisterReceiver() {
        mAdapterReceiver?.let {
            mContext.unregisterReceiver(it)
            mAdapterReceiver = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableBLEDeviceNotification(gatt: BluetoothGatt?, serviceUUID: UUID, characteristicUUID: UUID): Boolean {
        if (gatt == null || !AppUtil.checkHasConnectPermission(mContext)) {
            JL_Log.w(TAG, "Bluetooth gatt is null.")
            return false
        }
        val gattService = gatt.getService(serviceUUID)
        if (gattService == null) {
            JL_Log.w(TAG, "BluetoothGattService is null. uuid = $serviceUUID")
            return false
        }
        val characteristic = gattService.getCharacteristic(characteristicUUID)
        if (characteristic == null) {
            JL_Log.w(TAG, "BluetoothGattCharacteristic is null. uuid = $characteristicUUID")
            return false
        }
        var bRet = gatt.setCharacteristicNotification(characteristic, true)
        if (bRet) {
            bRet = false
            for (descriptor in characteristic.descriptors) {
                if (BLE_UUID_NOTIFICATION_DESCRIPTOR != descriptor.uuid) continue
                bRet = tryToWriteDescriptor(gatt, descriptor, 0, false)
                if (!bRet) {
                    JL_Log.w(TAG, "tryToWriteDescriptor failed....")
                } else {
                    break
                }
            }
        } else {
            JL_Log.w(TAG, "setCharacteristicNotification is failed....")
        }
        JL_Log.w(TAG, "enableBLEDeviceNotification ret : $bRet, serviceUUID : $serviceUUID, characteristicUUID : $characteristicUUID")
        return bRet
    }

    @SuppressLint("MissingPermission")
    private fun tryToWriteDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, retryCountIn: Int, isSkipSetValue: Boolean): Boolean {
        if (!AppUtil.checkHasConnectPermission(mContext)) return false
        var retryCount = retryCountIn
        var ret = isSkipSetValue
        if (!ret) {
            ret = descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            JL_Log.i(TAG, "..descriptor : .setValue  ret : $ret")
            if (!ret) {
                retryCount++
                return if (retryCount >= 3) {
                    false
                } else {
                    JL_Log.i(TAG, "-tryToWriteDescriptor- : retryCount : $retryCount, isSkipSetValue :  false")
                    SystemClock.sleep(50)
                    tryToWriteDescriptor(gatt, descriptor, retryCount, false)
                }
            } else {
                retryCount = 0
            }
        }
        if (ret) {
            ret = gatt.writeDescriptor(descriptor)
            JL_Log.i(TAG, "..bluetoothGatt : .writeDescriptor  ret : $ret")
            if (!ret) {
                retryCount++
                return if (retryCount >= 3) {
                    false
                } else {
                    JL_Log.i(TAG, "-tryToWriteDescriptor- 2222 : retryCount : $retryCount, isSkipSetValue :  true")
                    SystemClock.sleep(50)
                    tryToWriteDescriptor(gatt, descriptor, retryCount, true)
                }
            }
        }
        return ret
    }

    @SuppressLint("MissingPermission")
    private fun startChangeMtu(gatt: BluetoothGatt?, mtu: Int) {
        if (gatt == null || !AppUtil.checkHasConnectPermission(mContext)) {
            JL_Log.w(TAG, "-startChangeMtu- param is error.")
            return
        }
        val device = gatt.device
        if (device == null) {
            JL_Log.w(TAG, "-startChangeMtu- device is null.")
            return
        }
        if (mHandler.hasMessages(MSG_CHANGE_BLE_MTU_TIMEOUT)) {
            JL_Log.w(TAG, "-startChangeMtu- Adjusting the MTU for BLE")
            return
        }
        var ret = false
        if (mtu > BluetoothConstant.BLE_MTU_MIN) {
            ret = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                gatt.requestMtu(mtu + 3)
            } else {
                true
            }
        }
        JL_Log.d(TAG, "-startChangeMtu- ret = $ret")
        if (ret) {
            mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_CHANGE_BLE_MTU_TIMEOUT, device), CALLBACK_TIMEOUT)
        } else {
            handleBleConnectedEvent(device)
        }
    }

    private fun handleBleConnectedEvent(device: BluetoothDevice?) {
        if (device == null) {
            JL_Log.e(TAG, "-handleBleConnectedEvent- device is null.")
            return
        }
        val bleDevice = getConnectedBle(device) ?: return
        bleDevice.startSendDataThread()
        handleBleConnection(device, BluetoothProfile.STATE_CONNECTED)
    }

    private fun printDeviceInfo(device: BluetoothDevice?): String = BluetoothUtil.printBtDeviceInfo(mContext, device)

    private val mLeScanCallback = BluetoothAdapter.LeScanCallback { device, rssi, scanRecord ->
        filterDevice(device, rssi, scanRecord, true)
    }

    private val mScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result?.scanRecord != null) {
                val device = result.device
                var isBleEnableConnect = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    isBleEnableConnect = result.isConnectable
                }
                filterDevice(device, result.rssi, result.scanRecord!!.bytes, isBleEnableConnect)
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {}

        override fun onScanFailed(errorCode: Int) {
            JL_Log.d(TAG, "onScanFailed : $errorCode")
            stopLeScan()
        }
    }

    private val mBluetoothGattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (gatt == null || !AppUtil.checkHasConnectPermission(mContext)) return
            val device = gatt.device ?: return
            JL_Log.i(
                TAG,
                String.format(Locale.getDefault(), "onConnectionStateChange : device : %s, status = %d, newState = %d.", device.name, status, newState)
            )
            if (newState == BluetoothProfile.STATE_DISCONNECTED || newState == BluetoothProfile.STATE_DISCONNECTING || newState == BluetoothProfile.STATE_CONNECTED) {
                setConnectingBtDevice(null)
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    isConnected = true
                    mRetryConnectCount = 0
                    val ret = gatt.discoverServices()
                    JL_Log.d(TAG, "onConnectionStateChange >> discoverServices : $ret")
                    putConnectedGattInMap(device.address, gatt)
                    if (ret) {
                        mHandler.removeMessages(MSG_BLE_DISCOVER_SERVICES_CALLBACK_TIMEOUT)
                        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_BLE_DISCOVER_SERVICES_CALLBACK_TIMEOUT, device), CALLBACK_TIMEOUT)
                    } else {
                        disconnectBleDevice(device)
                    }
                    return
                } else {
                    removeConnectedBle(device)
                    AppUtil.refreshBleDeviceCache(mContext, gatt)
                    gatt.close()
                    val usedConnectTime = System.currentTimeMillis() - startConnectTime
                    JL_Log.d(TAG, "onConnectionStateChange >> usedConnectTime = $usedConnectTime, limit time = $MIN_CONNECT_TIME")
                    if (status == 133 && usedConnectTime < MIN_CONNECT_TIME) {
                        if (mRetryConnectCount < MAX_RETRY_CONNECT_COUNT) {
                            mRetryConnectCount++
                            connectBleDevice(device)
                            return
                        } else {
                            mRetryConnectCount = 0
                        }
                    }
                }
            }
            handleBleConnection(device, newState)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (gatt == null || !AppUtil.checkHasConnectPermission(mContext)) return
            val device = gatt.device ?: return
            mHandler.removeMessages(MSG_BLE_DISCOVER_SERVICES_CALLBACK_TIMEOUT)
            mCallbackManager.onBleServiceDiscovery(device, status, gatt.services)
            var ret = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                AppUtil.printBleGattServices(mContext, device, gatt, status)
                for (service in gatt.services) {
                    if (BLE_UUID_SERVICE == service.uuid && service.getCharacteristic(BLE_UUID_WRITE) != null && service.getCharacteristic(BLE_UUID_NOTIFICATION) != null) {
                        JL_Log.i(TAG, "start NotifyCharacteristicRunnable...")
                        val runnable = NotifyCharacteristicRunnable(gatt, BLE_UUID_SERVICE, BLE_UUID_NOTIFICATION)
                        mNotifyCharacteristicRunnable = runnable
                        mHandler.post(runnable)
                        ret = true
                        break
                    }
                }
            }
            JL_Log.i(TAG, "onServicesDiscovered : $ret")
            if (!ret) {
                disconnectBleDevice(device)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: android.bluetooth.BluetoothGattCharacteristic?) {
            if (gatt == null || !AppUtil.checkHasConnectPermission(mContext)) return
            val device = gatt.device
            if (device == null || characteristic == null) return
            val characteristicUUID = characteristic.uuid
            val data = characteristic.value
            val serviceUUID = characteristic.service?.uuid
            JL_Log.d(
                TAG,
                String.format(
                    Locale.getDefault(),
                    "onCharacteristicChanged : device : %s, serviceUuid = %s, characteristicUuid = %s, \ndata : [%s]",
                    printDeviceInfo(device), serviceUUID, characteristicUUID, CHexConver.byte2HexStr(data)
                )
            )
            mCallbackManager.onBleDataNotification(device, serviceUUID, characteristicUUID, data)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: android.bluetooth.BluetoothGattCharacteristic?, status: Int) {
            if (gatt?.device == null || characteristic == null || !AppUtil.checkHasConnectPermission(mContext)) return
            val device = gatt.device
            val characteristicUUID = characteristic.uuid
            val serviceUUID = characteristic.service?.uuid
            val data = characteristic.value
            JL_Log.d(
                TAG,
                String.format(
                    Locale.getDefault(),
                    "onCharacteristicWrite : device : %s, serviceUuid = %s, characteristicUuid = %s, status = %d, \ndata : [%s]",
                    printDeviceInfo(device), serviceUUID, characteristicUUID, status, CHexConver.byte2HexStr(data)
                )
            )
            wakeupSendThread(gatt, serviceUUID, characteristicUUID, status, data)
            mCallbackManager.onBleWriteStatus(device, serviceUUID, characteristicUUID, data, status)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (gatt == null || !AppUtil.checkHasConnectPermission(mContext)) return
            val device = gatt.device
            if (device == null || descriptor == null) return
            val characteristic = descriptor.characteristic
            val characteristicUuid = characteristic?.uuid
            val serviceUuid = characteristic?.service?.uuid
            JL_Log.i(
                TAG,
                String.format(
                    Locale.getDefault(),
                    "onDescriptorWrite : device : %s, serviceUuid = %s, characteristicUuid = %s, descriptor = %s, status = %d",
                    printDeviceInfo(device), serviceUuid, characteristicUuid, descriptor.uuid, status
                )
            )
            mCallbackManager.onBleNotificationStatus(device, serviceUuid, characteristicUuid, status)
            val runnable = mNotifyCharacteristicRunnable
            if (runnable != null && BluetoothUtil.deviceEquals(device, runnable.getBleDevice()) &&
                serviceUuid != null && serviceUuid == runnable.getServiceUUID() &&
                characteristicUuid != null && characteristicUuid == runnable.getCharacteristicUUID() &&
                descriptor.uuid == runnable.mDescriptorUUID
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mNotifyCharacteristicRunnable = null
                    var requestMTU = configHelper.getBleRequestMtu()
                    if (requestMTU > 509) requestMTU = 509
                    startChangeMtu(gatt, requestMTU)
                } else {
                    val num = runnable.getRetryNum()
                    if (num < 3) {
                        runnable.setRetryNum(num + 1)
                        mHandler.postDelayed(runnable, 100)
                    } else {
                        disconnectBleDevice(device)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (gatt == null || !AppUtil.checkHasConnectPermission(mContext)) return
            val device = gatt.device ?: return
            JL_Log.d(TAG, String.format(Locale.getDefault(), "onMtuChanged : device : %s, mtu = %d, status = %d", printDeviceInfo(device), mtu, status))
            mCallbackManager.onBleDataBlockChanged(device, mtu, status)
            val bleDevice = getConnectedBle(device)
            if (BluetoothGatt.GATT_SUCCESS == status) {
                val bleMtu = mtu - 3
                if (bleDevice != null && mHandler.hasMessages(MSG_CHANGE_BLE_MTU_TIMEOUT)) {
                    bleDevice.setMtu(bleMtu)
                    JL_Log.i(TAG, "-onMtuChanged- handleBleConnectedEvent")
                    handleBleConnectedEvent(device)
                }
            }
        }
    }

    private inner class BaseBtAdapterReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    var state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                    if (mBluetoothAdapter != null && state == -1) {
                        state = mBluetoothAdapter.state
                    }
                    if (state == BluetoothAdapter.STATE_OFF) {
                        isBleScanning(false)
                        mDiscoveredBleDevices.clear()
                        mCallbackManager.onDiscoveryBleChange(false)
                        disconnectBleDevice(getConnectedBtDevice())
                        mCallbackManager.onAdapterChange(false)
                    } else if (state == BluetoothAdapter.STATE_ON) {
                        mCallbackManager.onAdapterChange(true)
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    // Informational only; connection lifecycle is driven by GATT callbacks above.
                }
            }
        }
    }

    private inner class NotifyCharacteristicRunnable(
        private val mGatt: BluetoothGatt,
        private val mServiceUUID: UUID,
        private val mCharacteristicUUID: UUID
    ) : Runnable {
        val mDescriptorUUID: UUID = BLE_UUID_NOTIFICATION_DESCRIPTOR
        private var retryNum = 0

        fun setRetryNum(value: Int) {
            retryNum = value
        }

        fun getRetryNum(): Int = retryNum

        fun getBleDevice(): BluetoothDevice? = mGatt.device

        fun getServiceUUID(): UUID = mServiceUUID

        fun getCharacteristicUUID(): UUID = mCharacteristicUUID

        override fun run() {
            val ret = enableBLEDeviceNotification(mGatt, mServiceUUID, mCharacteristicUUID)
            JL_Log.w(
                TAG,
                String.format(Locale.getDefault(), "enableBLEDeviceNotification ===> %s, service uuid = %s, characteristic uuid = %s", ret, mServiceUUID, mCharacteristicUUID)
            )
            if (!ret) {
                disconnectBleDevice(mGatt.device)
            } else {
                mHandler.removeMessages(MSG_NOTIFY_BLE_TIMEOUT)
                mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_NOTIFY_BLE_TIMEOUT, mGatt.device), CALLBACK_TIMEOUT)
            }
        }
    }
}
