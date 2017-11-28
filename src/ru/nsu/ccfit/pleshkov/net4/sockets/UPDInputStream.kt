package ru.nsu.ccfit.pleshkov.net4.sockets

import ru.nsu.ccfit.pleshkov.net4.handlers.UDPStreamClosedException
import java.io.InputStream

class UPDInputStream(private val socket: UDPStreamSocket) : InputStream() {
    private val singleByte = ByteArray(1)

    override fun available() = socket.available()
    override fun close() = socket.close()

    override fun markSupported() = false

    override fun read() = try {
        socket.recv(singleByte, 0, 1)
        singleByte[0].toInt()
    } catch (e: UDPStreamClosedException) {
        -1
    }

    override fun read(buf: ByteArray?) = read(buf!!, 0, buf.size)

    override fun read(buf: ByteArray?, off: Int, len: Int) = try {
        socket.recv(buf!!, off, len)
    } catch (e: UDPStreamClosedException) {
        -1
    }
}
