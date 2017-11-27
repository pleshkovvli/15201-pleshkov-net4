package ru.nsu.ccfit.pleshkov.net4.sockets

import ru.nsu.ccfit.pleshkov.net4.handlers.UDPStreamClosedException
import java.io.IOException
import java.io.InputStream

class UPDInputStream(private val socket: UDPStreamSocket) : InputStream() {

    private val singleByte = ByteArray(1)

    override fun skip(amount: Long): Long {
        throw IOException()
    }

    override fun available(): Int {
        return socket.available()
    }

    override fun close() {
        socket.close()
    }

    override fun markSupported() = false

    override fun read(): Int {
        try {
            socket.recv(singleByte, 0, 1)
        } catch (e: UDPStreamClosedException) {
            return -1
        }
        return singleByte[0].toInt()
    }

    override fun read(buf: ByteArray?): Int {
        if(buf == null) {
            throw NullPointerException()
        }

        return read(buf, 0, buf.size)
    }

    override fun read(buf: ByteArray?, off: Int, len: Int): Int {
        buf!!

        return try {
            socket.recv(buf, off, len)
        } catch (e: UDPStreamClosedException) {
            -1
        }
    }
}