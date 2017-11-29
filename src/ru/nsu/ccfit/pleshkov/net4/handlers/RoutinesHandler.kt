package ru.nsu.ccfit.pleshkov.net4.handlers

import ru.nsu.ccfit.pleshkov.net4.messages.Message
import ru.nsu.ccfit.pleshkov.net4.messages.toPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.concurrent.thread

const val TIMEOUT_MS = 1000

open class UDPStreamSocketException(message: String) : Exception(message)

class UDPStreamSocketTimeoutException(reason: String)
    : UDPStreamSocketException("Time to $reason exceeded")

abstract class RoutinesHandler {
    private val recvRoutine = thread(start = false) {
        try {
            while (!Thread.interrupted()) {
                receivingRoutine()
            }
        } catch (e: InterruptedException) {
        }

        //println("RECVING ROUTINE on $this FINISHED")
    }

    private val sendRoutine = thread(start = false) {
        try {
            while (!Thread.interrupted()) {
                sendingRoutine()
            }
        } catch (e: InterruptedException) {
        }
        //println("SENDING ROUTINE on $this FINISHED")
    }

    protected var timeClose: Long = 0

    protected abstract val udpSocket: DatagramSocket

    open fun start() {
        sendRoutine.start()
        recvRoutine.start()
    }

    protected abstract fun sendingRoutine()
    protected abstract fun receivingRoutine()

    abstract fun connect(address: InetSocketAddress)

    abstract fun send(remote: InetSocketAddress, buf: ByteArray, offset: Int, length: Int): Int
    abstract fun recv(remote: InetSocketAddress, buf: ByteArray, offset: Int, length: Int): Int

    abstract fun closeConnection(remote: InetSocketAddress)

    abstract fun available(remote: InetSocketAddress): Int

    open fun finish() {
        timeClose = System.currentTimeMillis()
    }

    protected fun sendMessage(message: Message, remote: InetSocketAddress) {
        //println("SEND: $message on $this")
        udpSocket.send(message.toPacket(remote))
    }

    protected fun checkServiceMessages(remote: InetSocketAddress, messagesHandler: MessagesHandler) {
        do {
            val message = messagesHandler.currentServiceMessage() ?: break
            sendMessage(message, remote)
        } while (true)
    }

    protected fun checkData(remote: InetSocketAddress, messagesHandler: MessagesHandler) {
        do {
            val dataMessage = messagesHandler.currentDataMessage() ?: break
            sendMessage(dataMessage, remote)
        } while (true)
    }

    protected fun finishThreads() {
        sendRoutine.interrupt()
        recvRoutine.interrupt()
    }
}