package ru.nsu.ccfit.pleshkov.net4

import ru.nsu.ccfit.pleshkov.net4.messages.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue

class ServerRoutinesHandler : RoutinesHandler {

    constructor(port: Int) {
        udpSocket = DatagramSocket(port).apply { soTimeout = TIMEOUT_MS }
    }

    override val udpSocket: DatagramSocket

    private val messagesHandlers = HashMap<InetSocketAddress, MessagesHandler>()

    private val sendingHandlers = ArrayBlockingQueue<InetSocketAddress>(10)

    private val acceptingQueue = ArrayBlockingQueue<UDPStrSock>(10)

    fun accept() = acceptingQueue.take()

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
        messagesHandlers.values.forEach { it.checkResend() }

        val bytes = ByteArray(MESSAGE_BUFFER_SIZE)
        val packet = DatagramPacket(bytes, bytes.size)

        if(timeClose > 0 && timeToClose()) {
            finishThreads()
            return
        }

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

        val handler = messagesHandlers[remote]
        if(handler != null) {
            val state = handler.state
            val ack = handler.handleMessage(message)
            ack?.let {
                handler.serviceMessages.add(it)
            }
            sendingHandlers.put(remote)
            if(state == UDPStreamState.SYN_ACK_SENT && handler.state == UDPStreamState.CONNECTED) {
                val newClient = UDPStrSock(this, remote)
                acceptingQueue.add(newClient)
            }
        } else if(state == UDPStreamState.LISTENING) {
            val newHandler = MessagesHandler()
            val ack = newHandler.handleMessage(message)
            ack?.let { newHandler.serviceMessages.add(it) }
            messagesHandlers.put(remote, newHandler)
            sendingHandlers.put(remote)
        }
    }

    private fun timeToClose() = (System.currentTimeMillis() - timeClose) > 2 * TIMEOUT_MS

    override fun connect(address: InetSocketAddress) {
        throw Exception()
    }

    override fun send(remote: InetSocketAddress, buf: ByteArray, offset: Int, length: Int): Int {
        val messagesHandler = messagesHandlers[remote] ?: throw Exception()

        if(messagesHandler.state != UDPStreamState.CONNECTED) {
            throw Exception()
        }

        val sended = messagesHandler.send(buf, offset, length)
        sendingHandlers.add(remote)
        return sended
    }

    override fun recv(remote: InetSocketAddress, buf: ByteArray, offset: Int, length: Int): Int {
        val messagesHandler = messagesHandlers[remote] ?: throw UDPStreamSocketException("NO REMOTE")

        if(messagesHandler.state != UDPStreamState.CONNECTED) {
            throw UDPStreamSocketException("NOT CONNECTED")
        }

        return messagesHandler.recv(buf, offset, length)

    }

    override fun closeConnection(remote: InetSocketAddress) {
        val messagesHandler = messagesHandlers[remote] ?: return

        val fin = messagesHandler.currentFinMessage()
        messagesHandler.state = UDPStreamState.FIN_WAIT
        messagesHandler.serviceMessages.put(fin)
        sendingHandlers.add(remote)

        messagesHandler.waitTimeAck()

        if(state == UDPStreamState.CLOSED) {
            finish()
            finishThreads()
        }
    }

    fun closeServer() {
        state = UDPStreamState.CLOSED
    }


    private fun checkFin(remote: InetSocketAddress) {
        val messagesHandler = messagesHandlers[remote] ?: return

        messagesHandler.checkFin()?.let { sendBlocking(it, remote) }
    }

    private fun checkService(remote: InetSocketAddress) {
        val messagesHandler = messagesHandlers[remote] ?: return

        var message: Message? = messagesHandler.serviceMessages.poll()
        while (message != null) {
            sendBlocking(message, remote)
            message = messagesHandler.serviceMessages.poll()
        }
    }

    private fun handleData(remote: InetSocketAddress) {
        val dataMessage = messagesHandlers[remote]?.currentDataMessage()
        dataMessage?.let { sendBlocking(it, remote) }
    }

}