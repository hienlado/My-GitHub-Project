package com.hien.rtkmultidevice.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothConnection(
    private val device: BluetoothDevice
) {

    private var socket: BluetoothSocket? = null

    companion object {

        private val SERIAL_UUID: UUID =
            UUID.fromString(
                "00001101-0000-1000-8000-00805F9B34FB"
            )
    }

    @SuppressLint("MissingPermission")
    fun connect(): Boolean {

        return try {

            socket =
                device.createRfcommSocketToServiceRecord(
                    SERIAL_UUID
                )

            socket?.connect()

            true

        } catch (e: Exception) {

            e.printStackTrace()
            println(e.message)

            false
        }
    }

    fun getInputStream(): InputStream? {

        return socket?.inputStream
    }

    fun getOutputStream(): OutputStream? {

        return socket?.outputStream
    }

    fun disconnect() {

        socket?.close()
    }
}