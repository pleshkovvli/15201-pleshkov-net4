package ru.nsu.ccfit.pleshkov.net4.sockets

import ru.nsu.ccfit.pleshkov.net4.handlers.ClientRoutinesHandler
import ru.nsu.ccfit.pleshkov.net4.handlers.RoutinesHandler
import ru.nsu.ccfit.pleshkov.net4.handlers.UDPStreamSocketException
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress

class UDPStreamSocket : Closeable {
    val inetAddress: InetAddress?
        get() = remote?.address

    private val handler: RoutinesHandler
    private var remote: InetSocketAddress? = null

    constructor() {
        handler = ClientRoutinesHandler()
    }

    internal constructor(handler: RoutinesHandler, remote: InetSocketAddress) {
        this.handler = handler
        this.remote = remote
    }

    constructor(address: InetAddress, port: Int) {
        handler = ClientRoutinesHandler()
        connect(InetSocketAddress(address, port))
    }

    override fun close() {
        letRemote {
            handler.closeConnection(it)
            0
        }
    }

    fun getOutputStream(): OutputStream = UPDOutputStream(this)
    fun getInputStream(): InputStream = UPDInputStream(this)

    fun connect(address: InetSocketAddress) {
        remote = address
        handler.connect(address)
    }

    fun send(buf: ByteArray, offset: Int, length: Int) = letRemote {
        handler.send(it, buf, offset, length)
    }

    fun recv(buf: ByteArray, offset: Int, length: Int) = letRemote {
        handler.recv(it, buf, offset, length)
    }

    fun available() = letRemote { handler.available(it) }

    private fun letRemote(block: (InetSocketAddress) -> Int) = remote?.let {
        block(it)
    } ?: throw UDPStreamSocketException("Socket not connected")
}