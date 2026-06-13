package com.hien.rtkmultidevice.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

class BluetoothScanner(context: Context) {

    private val bluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager).adapter

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {

        val devices = mutableListOf<BluetoothDevice>()

        bluetoothAdapter?.bondedDevices?.forEach {
            devices.add(it)
        }

        return devices
    }
}