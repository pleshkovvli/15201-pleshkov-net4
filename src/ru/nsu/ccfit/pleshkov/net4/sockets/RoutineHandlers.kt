package ru.nsu.ccfit.pleshkov.net4.sockets

import ru.nsu.ccfit.pleshkov.net4.messages.BadBytesException
import ru.nsu.ccfit.pleshkov.net4.messages.MESSAGE_BUFFER_SIZE
import ru.nsu.ccfit.pleshkov.net4.messages.toMessage
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

const val TIMEOUT_MS = 1000

abstract class UDPStreamRoutineHandler {
    private lateinit var recvRoutine: Thread

    private lateinit var sendRoutine: Thread

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

        //sendRoutine.start()
        //recvRoutine.start()
    }

    protected abstract fun sendingRoutine()
    protected abstract fun receivingRoutine()


    abstract fun notifySended(sock: UDPStreamSocket)
    abstract fun notifyReceived()

    open fun finish() {
        sendRoutine.interrupt()
        recvRoutine.interrupt()
    }
}

class ClientRoutineHandler(private val socket: UDPStreamSocket) : UDPStreamRoutineHandler() {
    override fun sendingRoutine() {
        socket.checkFin()
        socket.handleData()
    }

    override fun notifySended(sock: UDPStreamSocket) {}

    override fun notifyReceived() {}

    override fun receivingRoutine() {
        if (System.currentTimeMillis() - socket.ackTimeStamp > TIMEOUT_MS) {
            socket.sendAgain()
        }

        val bytes = ByteArray(MESSAGE_BUFFER_SIZE)
        val packet = DatagramPacket(bytes, bytes.size)

        try {
            socket.udpSocket.receive(packet)
        } catch (e: SocketTimeoutException) {
            return
        }

        val message = try {
            bytes.toMessage()
        } catch (e: BadBytesException) {
            println(e.message)
            return
        }

        socket.handleMessage(message)
    }
}

class ServerRoutineHandler(private val socket: UDPStreamServerSocket) : UDPStreamRoutineHandler() {
    override fun sendingRoutine()  {
        val sock = socket.sendingQueue.take()
        sock.checkFin()
        sock.handleData()
    }

    override fun notifySended(sock: UDPStreamSocket) {
        socket.sendingQueue.put(sock)
    }

    override fun notifyReceived() {
    }

    override fun receivingRoutine() {
        socket.clientSockets.values.forEach { sock ->
            if (System.currentTimeMillis() - sock.ackTimeStamp > TIMEOUT_MS) {
                sock.sendAgain()
            }
        }

        val bytes = ByteArray(MESSAGE_BUFFER_SIZE)
        val packet = DatagramPacket(bytes, bytes.size)

        try {
            socket.udpSocket.receive(packet)
            println("PACKET RECVD")
        } catch (e: SocketTimeoutException) {
            return
        }

        val message = try {
            bytes.toMessage()
        } catch (e: BadBytesException) {
            println(e.message)
            return
        }

        val client = socket.clientSockets[packet.socketAddress]
        if(client != null) {
            println("HANDLING")
            client.handleMessage(message)
        } else {
            val newClient = UDPStreamSocket(socket.udpSocket, this)
            val added = socket.acceptingQueue.add(newClient)
            println("added: $added")
            socket.clientSockets.put(packet.socketAddress as InetSocketAddress, newClient)
        }
    }
}