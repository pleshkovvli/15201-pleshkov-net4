package ru.nsu.ccfit.pleshkov.net4

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress

class UDPStrSock : Closeable {
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

    fun connect(address: InetSocketAddress) {
        handler.connect(address)
    }

    fun send(buf: ByteArray, offset: Int, length: Int) : Int {
        return handler.send(remote, buf, offset, length)
    }

    fun recv(buf: ByteArray, offset: Int, length: Int) : Int {
        return handler.recv(remote, buf, offset, length)
    }

    override fun close() {
        handler.closeConnection(remote)
    }
}