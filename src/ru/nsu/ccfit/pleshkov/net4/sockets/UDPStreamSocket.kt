package ru.nsu.ccfit.pleshkov.net4.sockets

import ru.nsu.ccfit.pleshkov.net4.handlers.ClientRoutinesHandler
import ru.nsu.ccfit.pleshkov.net4.handlers.RoutinesHandler
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress

class UDPStreamSocket : Closeable {
    private val handler: RoutinesHandler
    private val remote: InetSocketAddress

    internal constructor(handler: RoutinesHandler, remote: InetSocketAddress) {
        this.handler = handler
        this.remote = remote
    }

    constructor(address: InetAddress, port: Int) {
        handler = ClientRoutinesHandler()
        remote = InetSocketAddress(address, port)
        handler.connect(remote)
    }

    fun getOutputStream() : OutputStream = UPDOutputStream(this)
    fun getInputStream() : InputStream = UPDInputStream(this)

    fun connect(address: InetSocketAddress) {
        handler.connect(address)
    }

    fun send(buf: ByteArray, offset: Int, length: Int) : Int {
        return handler.send(remote, buf, offset, length)
    }

    fun recv(buf: ByteArray, offset: Int, length: Int) : Int {
        return handler.recv(remote, buf, offset, length)
    }

    fun available() = handler.available(remote)

    override fun close() {
        handler.closeConnection(remote)
    }
}