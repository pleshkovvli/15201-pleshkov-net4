package ru.nsu.ccfit.pleshkov.net4

import ru.nsu.ccfit.pleshkov.net4.messages.Message
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

abstract class RoutinesHandler {
    private lateinit var recvRoutine: Thread
    private lateinit var sendRoutine: Thread

    protected var timeClose: Long = 0

    protected abstract val udpSocket: DatagramSocket

    private val serviceMessages = ArrayBlockingQueue<Message>(50)

    open fun start() {
        sendRoutine = thread {
            while (!Thread.interrupted()) {
                sendingRoutine()
            }
        }

        recvRoutine = thread {
            while (!Thread.interrupted()) {
                receivingRoutine()
            }
        }
    }

    protected abstract fun sendingRoutine()
    protected abstract fun receivingRoutine()

    abstract fun connect(address: InetSocketAddress)

    abstract fun send(id: Int, buf: ByteArray, offset: Int, length: Int) : Int
    abstract fun recv(id: Int, buf: ByteArray, offset: Int, length: Int) : Int

    open fun finish() {
        timeClose = System.currentTimeMillis()
    }

    protected fun finishThreads() {
        sendRoutine.interrupt()
        recvRoutine.interrupt()
    }
}