package ru.nsu.ccfit.pleshkov.net4.sockets

import ru.nsu.ccfit.pleshkov.net4.handlers.ServerRoutinesHandler
import java.io.Closeable

class UDPStreamServerSocket(port: Int) : Closeable {
    val isClosed
        get() = handler.closed

    private val handler: ServerRoutinesHandler = ServerRoutinesHandler(port).apply { listen() }

    override fun close() = handler.closeServer()

    fun listen() = handler.listen()

    fun accept() : UDPStreamSocket = handler.accept()
}