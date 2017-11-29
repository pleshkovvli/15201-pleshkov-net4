package ru.nsu.ccfit.pleshkov.net4.sockets

import ru.nsu.ccfit.pleshkov.net4.handlers.ServerRoutinesHandler
import java.io.Closeable

class UDPStreamServerSocket(port: Int) : Closeable {
    private val handler: ServerRoutinesHandler = ServerRoutinesHandler(port).apply { listen() }

    val isClosed
        get() = handler.closed

    fun listen() = handler.listen()

    fun accept() : UDPStreamSocket = handler.accept()

    override fun close() = handler.closeServer()
}