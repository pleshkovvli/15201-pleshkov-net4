package ru.nsu.ccfit.pleshkov.net4.handlers

import ru.nsu.ccfit.pleshkov.net4.messages.*
import java.net.*

const val TIME_TO_CONNECT_MS = 5000

class ClientRoutinesHandler(port: Int? = null) : RoutinesHandler() {
    override val udpSocket = port?.let { DatagramSocket(port) } ?: DatagramSocket()

    private val messagesHandler = MessagesHandler()
    private val sendingLock = java.lang.Object()

    private var sendRoutineRun: Boolean = false

    private lateinit var remoteAddress: InetSocketAddress

    init {
        udpSocket.soTimeout = TIMEOUT_MS
    }

    override fun connect(address: InetSocketAddress) {
        if (!messagesHandler.notConnected) {
            throw UDPStreamSocketException("Socket already connected")
        }

        remoteAddress = address

        connectAction(address)
        start()
    }

    override fun send(remote: InetSocketAddress, buf: ByteArray, offset: Int, length: Int): Int {
        if (!messagesHandler.connected) {
            throw UDPStreamNotConnectedException()
        }

        val sent = messagesHandler.send(buf, offset, length)
        notifySent()
        return sent
    }

    override fun recv(remote: InetSocketAddress, buf: ByteArray, offset: Int, length: Int): Int {
        if (!messagesHandler.connected) {
            throw UDPStreamNotConnectedException()
        }

        return messagesHandler.recv(buf, offset, length)
    }

    override fun closeConnection(remote: InetSocketAddress) {
        if (!messagesHandler.connected) return

        if (messagesHandler.fin()) {
            finish()
        }
    }

    override fun available(remote: InetSocketAddress) = messagesHandler.available

    override fun sendingRoutine() {
        waitOnSend()

        checkServiceMessages(remoteAddress, messagesHandler)
        checkData(remoteAddress, messagesHandler)
    }

    override fun receivingRoutine() {
        if(messagesHandler.checkResend()) {
            notifySent()
        }

        if(timeClose > 0 && timeToClose()) {
            finishThreads()
            try {
                udpSocket.close()
            } catch (e: SocketException) {
            }
            return
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
            return
        }

        val sendAck = messagesHandler.handleMessage(message)

        if (sendAck) {
            notifySent()
        }
    }

    private fun timeToClose() = (System.currentTimeMillis() - timeClose) > 2 * TIMEOUT_MS

    private fun waitOnSend() = synchronized(sendingLock) {
        while (!sendRoutineRun) {
            sendingLock.wait()
        }
        sendRoutineRun = false
    }

    private fun notifySent() = synchronized(sendingLock) {
        sendRoutineRun = true
        sendingLock.notifyAll()
    }

    private fun connectAction(remote: InetSocketAddress) {
        val timestamp = System.currentTimeMillis()

        val syn = messagesHandler.initSynMessage()

        val synackBuffer = ByteArray(SERVICE_BUFFER_SIZE)
        val synack = DatagramPacket(synackBuffer, synackBuffer.size)

        while (gotTimeToConnect(timestamp) && !messagesHandler.connected) {
            try {
                sendMessage(syn, remote)
            } catch (e: SocketTimeoutException) {
                continue
            }

            try {
                udpSocket.receive(synack)
            } catch (e: SocketTimeoutException) {
                continue
            }

            val message = try {
                synackBuffer.toMessage()
            } catch (e: BadBytesException) {
                continue
            }

            if(message is FinMessage) {
                throw UDPStreamSocketException("Failed to connect with closing server")
            }

            message as? SynAckMessage ?: continue

            sendRoutineRun = messagesHandler.handleMessage(message)
        }

        if (!messagesHandler.connected) {
            throw UDPStreamSocketTimeoutException("connect")
        }
    }

    private fun gotTimeToConnect(timestamp: Long): Boolean {
        return (System.currentTimeMillis() - timestamp) < TIME_TO_CONNECT_MS
    }
}