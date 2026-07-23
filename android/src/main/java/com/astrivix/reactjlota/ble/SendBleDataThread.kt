package com.astrivix.reactjlota.ble

import android.bluetooth.BluetoothGatt
import com.astrivix.reactjlota.ble.interfaces.IBleOp
import com.astrivix.reactjlota.ble.interfaces.OnThreadStateListener
import com.astrivix.reactjlota.ble.interfaces.OnWriteDataCallback
import com.jieli.jl_bt_ota.util.JL_Log
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * Serializes BLE writes: one in-flight GATT write at a time, waiting for the
 * characteristic-write callback (woken via [wakeupSendThread]) before sending the
 * next queued block, with up to 3 retries per block before failing the whole task.
 */
class SendBleDataThread(
    private val bleManager: IBleOp?,
    private val listener: OnThreadStateListener?
) : Thread(TAG) {

    companion object {
        private const val TAG = "SendBleDataThread"
    }

    private val queue = LinkedBlockingQueue<BleSendTask>()

    @Volatile
    private var isDataSend = false
    private var isThreadWaiting = false
    private var isWaitingForCallback = false
    private var retryNum = 0

    private var currentTask: BleSendTask? = null

    fun isRunning(): Boolean = isDataSend

    fun addSendTask(gatt: BluetoothGatt, serviceUUID: UUID, characteristicUUID: UUID, data: ByteArray, callback: OnWriteDataCallback?): Boolean {
        val manager = bleManager ?: return false
        if (data.isEmpty()) return false
        val mtu = manager.getBleMtu()
        JL_Log.d(TAG, "addSendTask : $mtu")
        val dataLen = data.size
        val blockCount = dataLen / mtu
        var ret = false
        for (i in 0 until blockCount) {
            val blockData = ByteArray(mtu)
            System.arraycopy(data, i * mtu, blockData, 0, blockData.size)
            ret = addSendData(gatt, serviceUUID, characteristicUUID, blockData, callback)
        }
        if (dataLen % mtu != 0) {
            val noBlockData = ByteArray(dataLen % mtu)
            System.arraycopy(data, dataLen - dataLen % mtu, noBlockData, 0, noBlockData.size)
            ret = addSendData(gatt, serviceUUID, characteristicUUID, noBlockData, callback)
        }
        return ret
    }

    fun wakeupSendThread(sendTask: BleSendTask?) {
        val current = currentTask
        if (sendTask == null || (current != null && current == sendTask)) {
            if (sendTask != null) {
                sendTask.callback = current?.callback
                currentTask = sendTask
            }
            synchronized(queue) {
                if (isThreadWaiting) {
                    if (isWaitingForCallback) {
                        (queue as java.lang.Object).notifyAll()
                    } else {
                        (queue as java.lang.Object).notify()
                    }
                } else if (isWaitingForCallback) {
                    (queue as java.lang.Object).notify()
                }
            }
        }
    }

    private fun addSendData(gatt: BluetoothGatt, serviceUUID: UUID, characteristicUUID: UUID, data: ByteArray, callback: OnWriteDataCallback?): Boolean {
        var ret = false
        if (isDataSend) {
            val sendTask = BleSendTask(gatt, serviceUUID, characteristicUUID, data, callback)
            try {
                queue.put(sendTask)
                ret = true
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            if (ret && isThreadWaiting && !isWaitingForCallback) {
                isThreadWaiting = false
                synchronized(queue) { (queue as java.lang.Object).notify() }
            }
        }
        return ret
    }

    @Synchronized
    override fun start() {
        isDataSend = true
        super.start()
    }

    @Synchronized
    fun stopThread() {
        isDataSend = false
        wakeupSendThread(null)
    }

    private fun callbackResult(task: BleSendTask?, result: Boolean) {
        if (task?.callback != null) {
            val gatt = task.bleGatt ?: return
            task.callback?.onBleResult(gatt.device, task.serviceUUID, task.characteristicUUID, result, task.data)
        } else {
            JL_Log.i(TAG, "getCallback is null.")
        }
    }

    override fun run() {
        JL_Log.d(TAG, "send ble data thread is started.")
        listener?.onStart(id, name)
        if (bleManager != null) {
            synchronized(queue) {
                while (isDataSend) {
                    currentTask = null
                    isThreadWaiting = false
                    isWaitingForCallback = false
                    if (queue.isEmpty()) {
                        isThreadWaiting = true
                        JL_Log.d(TAG, "queue is empty, so waiting for data")
                        try {
                            (queue as java.lang.Object).wait()
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }
                    } else {
                        val task = queue.peek()
                        currentTask = task
                        if (task != null) {
                            isWaitingForCallback = bleManager.writeDataByBle(task.mGatt, task.serviceUUID, task.characteristicUUID, task.data)
                            if (isWaitingForCallback) {
                                try {
                                    (queue as java.lang.Object).wait(BleManager.SEND_DATA_MAX_TIMEOUT.toLong())
                                } catch (e: InterruptedException) {
                                    e.printStackTrace()
                                }
                            } else {
                                task.status = -1
                            }
                            JL_Log.d(TAG, "data send ret :" + task.status)
                            if (task.status != BluetoothGatt.GATT_SUCCESS) {
                                retryNum++
                                if (retryNum >= 3) {
                                    callbackResult(task, false)
                                    queue.clear()
                                } else {
                                    if (task.status != -1) {
                                        task.status = -1
                                        try {
                                            sleep(10)
                                        } catch (e: InterruptedException) {
                                            e.printStackTrace()
                                        }
                                    }
                                    continue
                                }
                            } else {
                                callbackResult(task, true)
                            }
                        }
                        retryNum = 0
                        if (!queue.isEmpty()) queue.poll()
                    }
                }
            }
            isWaitingForCallback = false
            isThreadWaiting = false
            queue.clear()
            listener?.onEnd(id, name)
            JL_Log.d(TAG, "send ble data thread exit.")
        }
    }

    class BleSendTask(
        val mGatt: BluetoothGatt,
        val serviceUUID: UUID,
        val characteristicUUID: UUID,
        val data: ByteArray,
        var callback: OnWriteDataCallback?
    ) {
        var status: Int = -1

        val bleGatt: BluetoothGatt get() = mGatt

        override fun toString(): String =
            "BleSendTask{mGatt=$mGatt, serviceUUID=$serviceUUID, characteristicUUID=$characteristicUUID, data=${data.contentToString()}, status=$status, callback=$callback}"

        override fun hashCode(): Int = mGatt.hashCode() + serviceUUID.hashCode() + characteristicUUID.hashCode()

        override fun equals(other: Any?): Boolean {
            if (other !is BleSendTask) return false
            return mGatt == other.mGatt && serviceUUID == other.serviceUUID && characteristicUUID == other.characteristicUUID
        }
    }
}
