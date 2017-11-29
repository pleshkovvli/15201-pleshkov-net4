package ru.nsu.ccfit.pleshkov.net4.handlers

import ru.nsu.ccfit.pleshkov.net4.messages.*
import ru.nsu.ccfit.pleshkov.net4.sockets.UDPStreamSocket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue

class UDPStreamClosedException : UDPStreamSocketException("Socket closed")

class ServerRoutinesHandler(port: Int) : RoutinesHandler() {

    override val udpSocket = DatagramSocket(port).apply { soTimeout = TIMEOUT_MS }

    private val messagesHandlers = HashMap<InetSocketAddress, MessagesHandler>()

    private val sendingHandlers = ArrayBlockingQueue<InetSocketAddress>(10)

    private val acceptingQueue = ArrayBlockingQueue<UDPStreamSocket>(10)

    fun accept() : UDPStreamSocket = acceptingQueue.take()

    override fun available(remote: InetSocketAddress) = messagesHandlers[remote]?.available ?: -1

    private var state: UDPStreamState = UDPStreamState.NOT_CONNECTED

    fun listen() {
        state = UDPStreamState.LISTENING
        start()
    }

    override fun sendingRoutine() {
        val remote = sendingHandlers.take()
        val messagesHandler = messagesHandlers[remote] ?: return

        checkServiceMessages(remote, messagesHandler)
        checkData(remote, messagesHandler)
    }

    override fun receivingRoutine() {
        //println("HANDLERS")
        if (state == UDPStreamState.CLOSED && messagesHandlers.isEmpty()) {
            finish()
            finishThreads()
        }

        chechHandlers()

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

        println("RECV $message on $this")

        val remote = packet.socketAddress as? InetSocketAddress ?: return
        val handler = messagesHandlers[remote]

        if (handler != null) {
            handleClient(message, remote, handler)
        } else {
            handleNewClient(message, remote)
        }
    }

    private fun chechHandlers() {
        messagesHandlers.forEach { pair ->
            val handler = pair.value

            if (handler.closed()) {
                messagesHandlers.remove(pair.key)
                handler.closeBuffers()
                if (messagesHandlers.isEmpty()) {
                    finish()
                    finishThreads()
                }
            } else {
                if(handler.checkResend()) {
                    sendingHandlers.put(pair.key)
                }
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

    override fun connect(address: InetSocketAddress) {
        throw UDPStreamSocketException("Server side socket")
    }

    override fun send(remote: InetSocketAddress, buf: ByteArray, offset: Int, length: Int): Int {
        val messagesHandler = messagesHandlers[remote] ?: throw UDPStreamClosedException()

        if (!messagesHandler.connected) {
            throw UDPStreamSocketException("Socket is not connected")
        }

        val sended = messagesHandler.send(buf, offset, length)
        sendingHandlers.put(remote)
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

        if (messagesHandler.state != UDPStreamState.CONNECTED) {
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

    fun closeServer() {
        state = UDPStreamState.CLOSED
        if (messagesHandlers.isEmpty()) {
            finish()
            finishThreads()
        }
    }
}