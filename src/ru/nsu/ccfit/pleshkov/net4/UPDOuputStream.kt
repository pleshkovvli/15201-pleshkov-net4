package ru.nsu.ccfit.pleshkov.net4

import java.io.OutputStream

class UPDOutputStream(private val socket: UDPStrSock) : OutputStream() {
    private val singleByte = ByteArray(1)

    override fun write(byte: Int) {
        singleByte[0] = byte.toByte()
        socket.send(singleByte, 0, 1)
    }

    override fun write(bytes: ByteArray?) {
        bytes!!
        socket.send(bytes, 0, bytes.size)
    }

    override fun write(bytes: ByteArray?, off: Int, len: Int) {
        bytes!!
        socket.send(bytes, off, len)
    }

    override fun flush() {
        super.flush()
    }

    override fun close() {
        socket.close()
    }
}