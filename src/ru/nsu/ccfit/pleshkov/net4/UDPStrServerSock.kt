package ru.nsu.ccfit.pleshkov.net4

import java.io.Closeable

class UDPStrServerSock : Closeable {
    private val handler: ServerRoutinesHandler

    constructor(port: Int) {
        handler = ServerRoutinesHandler(port)
    }

    fun listen() = handler.listen()

    fun accept() = handler.accept()

    override fun close() = handler.closeServer()
}