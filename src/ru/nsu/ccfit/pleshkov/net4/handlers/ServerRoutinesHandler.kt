package ru.nsu.ccfit.pleshkov.net4.handlers

import ru.nsu.ccfit.pleshkov.net4.messages.*
import ru.nsu.ccfit.pleshkov.net4.sockets.UDPStreamSocket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue

class UDPStreamClosedException : UDPStreamSocketException("Socket closed")

class ServerRoutinesHandler : RoutinesHandler {

    constructor(port: Int) {
        udpSocket = DatagramSocket(port).apply { soTimeout = TIMEOUT_MS }
    }

    override val udpSocket: DatagramSocket

    private val messagesHandlers = HashMap<InetSocketAddress, MessagesHandler>()

    private val sendingHandlers = ArrayBlockingQueue<InetSocketAddress>(10)

    private val acceptingQueue = ArrayBlockingQueue<UDPStreamSocket>(10)

    fun accept() = acceptingQueue.take()

    override fun available(remote: InetSocketAddress) = messagesHandlers[remote]?.available ?: -1

    private var state: UDPStreamState = UDPStreamState.NOT_CONNECTED

    fun listen() {
        state = UDPStreamState.LISTENING

        start()
    }

    override fun sendingRoutine() {
        val remote = sendingHandlers.take()

        checkService(remote)
        handleData(remote)
        checkFin(remote)
    }

    override fun receivingRoutine() {
        //println("HANDLERS")
        if (state == UDPStreamState.CLOSED && messagesHandlers.isEmpty()) {
            finish()
            finishThreads()
        }

        if (timeClose > 0 && timeToClose()) {
            finishThreads()
            return
        }

        messagesHandlers.forEach { pair ->
            //println(pair)
            val handler = pair.value

            if (handler.closed()) {
                messagesHandlers.remove(pair.key)
                handler.closeBuffers()
                if (messagesHandlers.isEmpty()) {
                    finish()
                    finishThreads()
                }
            } else {
                handler.checkResend()
            }
        }

        val bytes = ByteArray(MESSAGE_BUFFER_SIZE)
        val packet = DatagramPacket(bytes, bytes.size)

        try {
            udpSocket.receive(packet)
        } catch (e: SocketTimeoutException) {
            return
        }

        val message = try {
            bytes.toMessage()
        } catch (e: BadBytesException) {
            println(e.message)
            return
        }

        val remote = packet.socketAddress as? InetSocketAddress ?: return

        println(message)

        val handler = messagesHandlers[remote]
        if (handler != null) {
            val wasConnected = handler.connected

            val sendAck = handler.handleMessage(message)
            if (sendAck) {
                sendingHandlers.put(remote)
            }

            if (!wasConnected && handler.connected) {
                val newClient = UDPStreamSocket(this, remote)
                acceptingQueue.add(newClient)
            }
        } else {
            if (state == UDPStreamState.LISTENING) {
                message as? SynMessage ?: return

                val newHandler = MessagesHandler()
                messagesHandlers.put(remote, newHandler)

                val sendAck = newHandler.handleMessage(message)
                if(sendAck) {
                    sendingHandlers.put(remote)
                }
            } else {
                sendMessage(FinMessage(0,0), remote)
            }
        }
    }

    private fun timeToClose() = (System.currentTimeMillis() - timeClose) > 2 * TIMEOUT_MS

    override fun connect(address: InetSocketAddress) {
        throw UDPStreamSocketException("Server side socket")
    }

    override fun send(remote: InetSocketAddress, buf: ByteArray, offset: Int, length: Int): Int {
        val messagesHandler = messagesHandlers[remote] ?: throw UDPStreamClosedException()

        if (!messagesHandler.connected) {
            throw UDPStreamSocketException("Socket is not connected")
        }

        val sended = messagesHandler.send(buf, offset, length)
        sendingHandlers.add(remote)
        return sended
    }

    override fun recv(remote: InetSocketAddress, buf: ByteArray, offset: Int, length: Int): Int {
        val messagesHandler = messagesHandlers[remote] ?: throw UDPStreamClosedException()

        if (!messagesHandler.connected) {
            throw UDPStreamSocketException("Socket is not connected")
        }

        return messagesHandler.recv(buf, offset, length)

    }

    override fun closeConnection(remote: InetSocketAddress) {
        val messagesHandler = messagesHandlers[remote] ?: throw UDPStreamClosedException()

        if (messagesHandler.state != UDPStreamState.CONNECTED) return

        if (!messagesHandler.fin()) return

        messagesHandlers.remove(remote)

        if (state == UDPStreamState.CLOSED && messagesHandlers.isEmpty()) {
            finish()
            finishThreads()
        }
    }

    fun closeServer() {
        state = UDPStreamState.CLOSED
        if (messagesHandlers.isEmpty()) {
            finish()
            finishThreads()
        }
    }


    private fun checkFin(remote: InetSocketAddress) {
        val messagesHandler = messagesHandlers[remote] ?: return

        messagesHandler.checkFin()?.let { sendMessage(it, remote) }
    }

    private fun checkService(remote: InetSocketAddress) {
        val messagesHandler = messagesHandlers[remote] ?: return

        var message: Message? = messagesHandler.currentServiceMessage()
        while (message != null) {
            sendMessage(message, remote)
            message = messagesHandler.currentServiceMessage()
        }
    }

    private fun handleData(remote: InetSocketAddress) {
        val messagesHandler = messagesHandlers[remote] ?: return
        var dataMessage = messagesHandler.currentDataMessage()
        while (dataMessage != null) {
            sendMessage(dataMessage, remote)
            dataMessage = messagesHandler.currentDataMessage()
        }
    }

}