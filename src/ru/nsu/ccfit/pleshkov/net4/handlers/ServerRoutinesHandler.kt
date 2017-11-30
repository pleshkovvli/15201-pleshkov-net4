package ru.nsu.ccfit.pleshkov.net4.handlers

import ru.nsu.ccfit.pleshkov.net4.messages.*
import ru.nsu.ccfit.pleshkov.net4.sockets.UDPStreamSocket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap

class ServerRoutinesHandler(port: Int) : RoutinesHandler() {
    override val udpSocket = DatagramSocket(port).apply { soTimeout = TIMEOUT_MS }

    val closed
        get() = (state == UDPStreamState.CLOSED)

    private val messagesHandlers = ConcurrentHashMap<InetSocketAddress, MessagesHandler>()
    private val sendingHandlers = ArrayBlockingQueue<InetSocketAddress>(20)
    private val acceptingQueue = ArrayBlockingQueue<UDPStreamSocket>(20)

    private var state: UDPStreamState = UDPStreamState.NOT_CONNECTED

    override fun connect(address: InetSocketAddress) {
        throw UDPStreamSocketException("Server side socket")
    }

    override fun send(remote: InetSocketAddress, buf: ByteArray, offset: Int, length: Int): Int {
        val messagesHandler = messagesHandlers[remote] ?: throw UDPStreamClosedException()

        if (!messagesHandler.connected) {
            throw UDPStreamSocketException("Socket is not connected")
        }

        val sent = messagesHandler.send(buf, offset, length)
        sendingHandlers.put(remote)
        return sent
    }

    override fun recv(remote: InetSocketAddress, buf: ByteArray, offset: Int, length: Int): Int {
        val messagesHandler = messagesHandlers[remote] ?: throw UDPStreamClosedException()

        if (!messagesHandler.connected) {
            throw UDPStreamSocketException("Socket is not connected")
        }

        return messagesHandler.recv(buf, offset, length)

    }

    override fun closeConnection(remote: InetSocketAddress) {
        val messagesHandler = messagesHandlers[remote] ?: return

        if (!messagesHandler.connected) {
            return
        }

        if (!messagesHandler.fin()) {
            return
        }

        messagesHandlers.remove(remote)

        if (state == UDPStreamState.CLOSED && messagesHandlers.isEmpty()) {
            finish()
            finishThreads()
        }
    }

    override fun available(remote: InetSocketAddress) = messagesHandlers[remote]?.available ?: -1

    override fun sendingRoutine() {
        val remote = sendingHandlers.take()
        val messagesHandler = messagesHandlers[remote] ?: return

        checkServiceMessages(remote, messagesHandler)
        checkData(remote, messagesHandler)
    }

    override fun receivingRoutine() {
        if (state == UDPStreamState.CLOSED && messagesHandlers.isEmpty()) {
            finish()
            finishThreads()
        }

        checkHandlers()

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
            return
        }

        val remote = packet.socketAddress as? InetSocketAddress ?: return
        val handler = messagesHandlers[remote]

        if (handler != null) {
            handleClient(message, remote, handler)
        } else {
            handleNewClient(message, remote)
        }
    }

    fun accept(): UDPStreamSocket {
        if (state != UDPStreamState.LISTENING) {
            throw UDPStreamNotConnectedException()
        }
        return acceptingQueue.take()
    }

    fun listen() = when (state) {
        UDPStreamState.LISTENING -> throw UDPStreamSocketException("Already listening")
        UDPStreamState.CLOSED -> throw UDPStreamClosedException()
        else -> {
            state = UDPStreamState.LISTENING
            start()
        }
    }

    fun closeServer() {
        state = UDPStreamState.CLOSED
        if (messagesHandlers.isEmpty()) {
            finish()
            finishThreads()
        }
    }

    private fun checkHandlers() = messagesHandlers.forEach { entry ->
        val handler = entry.value

        if (handler.closed()) {
            messagesHandlers.remove(entry.key)
            handler.closeBuffers()
            if (state == UDPStreamState.CLOSED && messagesHandlers.isEmpty()) {
                finish()
                finishThreads()
            }
        } else {
            if (handler.checkResend()) {
                sendingHandlers.put(entry.key)
            }
        }
    }

    private fun handleNewClient(message: Message, remote: InetSocketAddress) {
        if (state == UDPStreamState.LISTENING) {
            message as? SynMessage ?: return

            val newHandler = MessagesHandler()
            messagesHandlers.put(remote, newHandler)

            val sendAck = newHandler.handleMessage(message)
            if (sendAck) {
                sendingHandlers.put(remote)
            }
        } else {
            sendMessage(FinMessage(0, 0), remote)
        }
    }

    private fun handleClient(message: Message, remote: InetSocketAddress, handler: MessagesHandler) {
        val wasConnected = handler.connected

        val sendAck = handler.handleMessage(message)
        if (sendAck) {
            sendingHandlers.put(remote)
        }

        if (!wasConnected && handler.connected) {
            val newClient = UDPStreamSocket(this, remote)
            acceptingQueue.add(newClient)
        }
    }
}