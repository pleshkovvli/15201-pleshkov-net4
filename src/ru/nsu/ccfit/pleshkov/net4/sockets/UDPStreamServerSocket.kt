package ru.nsu.ccfit.pleshkov.net4.sockets

import ru.nsu.ccfit.pleshkov.net4.handlers.ServerRoutinesHandler
import java.io.Closeable

class UDPStreamServerSocket : Closeable {
    private val handler: ServerRoutinesHandler

    constructor(port: Int) {
        handler = ServerRoutinesHandler(port)
    }

    fun listen() = handler.listen()

    fun accept() : UDPStreamSocket = handler.accept()

    override fun close() = handler.closeServer()
}