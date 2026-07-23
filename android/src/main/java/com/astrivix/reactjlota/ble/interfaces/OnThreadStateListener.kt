package com.astrivix.reactjlota.ble.interfaces

interface OnThreadStateListener {

    fun onStart(id: Long, name: String)

    fun onEnd(id: Long, name: String)
}
