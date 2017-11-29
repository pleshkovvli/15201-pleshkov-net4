package ru.nsu.ccfit.pleshkov.net4.handlers

import ru.nsu.ccfit.pleshkov.net4.messages.*
import java.net.*

const val TIME_TO_CONNECT_MS = 5000

class ClientRoutinesHandler(port: Int? = null) : RoutinesHandler() {

    override val udpSocket = port?.let { DatagramSocket(port) } ?: DatagramSocket()

    init {
        udpSocket.soTimeout = TIMEOUT_MS
    }

    private val messagesHandler = MessagesHandler()
    private lateinit var remoteAddress: InetSocketAddress

    private val sendingLock = java.lang.Object()
    private var sendRoutineRun: Boolean = false

    override fun sendingRoutine() {
        waitOnSend()

        checkServiceMessages(remoteAddress, messagesHandler)
        checkData(remoteAddress, messagesHandler)
    }

    private fun waitOnSend() = synchronized(sendingLock) {
        while (!sendRoutineRun) {
            sendingLock.wait()
        }
        sendRoutineRun = false
    }

    override fun send(remote: InetSocketAddress, buf: ByteArray, offset: Int, length: Int): Int {
        if (messagesHandler.state != UDPStreamState.CONNECTED) {
            throw Exception()
        }

        val sended = messagesHandler.send(buf, offset, length)
        notifySended()
        return sended
    }

    override fun recv(remote: InetSocketAddress, buf: ByteArray, offset: Int, length: Int): Int {
        if (messagesHandler.state != UDPStreamState.CONNECTED) {
            throw Exception()
        }

        return messagesHandler.recv(buf, offset, length)
    }

    override fun available(remote: InetSocketAddress) = messagesHandler.available

    override fun receivingRoutine() {
        if(messagesHandler.checkResend()) {
            notifySended()
        }

        val bytes = ByteArray(MESSAGE_BUFFER_SIZE)
        val packet = DatagramPacket(bytes, bytes.size)

        try {
            udpSocket.receive(packet)
        } catch (e: SocketTimeoutException) {
            if (messagesHandler.closed()) {
                finishThreads()
                try {
                    udpSocket.close()
                } catch (e: SocketException) {
                }
            }
            return
        }

        val message = try {
            bytes.toMessage()
        } catch (e: BadBytesException) {
            return
        }

        //println("RECV: $message on $this")

        val sendAck = messagesHandler.handleMessage(message)

        if (sendAck) {
            notifySended()
        }
    }

    private fun timeToClose() = (System.currentTimeMillis() - timeClose) > 2 * TIMEOUT_MS

    override fun closeConnection(remote: InetSocketAddress) {
        if (messagesHandler.state != UDPStreamState.CONNECTED) return

        if (messagesHandler.fin()) {
            finish()
        }
    }

    private fun notifySended() = synchronized(sendingLock) {
        sendRoutineRun = true
        sendingLock.notifyAll()
    }


    override fun connect(address: InetSocketAddress) {
        if (messagesHandler.state != UDPStreamState.NOT_CONNECTED) {
            throw UDPStreamSocketException("Socket already connected")
        }

        remoteAddress = address

        connectAction(address)
        start()
    }

    private fun connectAction(remote: InetSocketAddress) {
        val timestamp = System.currentTimeMillis()

        //udpSocket.connect(remoteAddress)

        val syn = messagesHandler.initSynMessage()

        val synackBuffer = ByteArray(SERVICE_BUFFER_SIZE)
        val synack = DatagramPacket(synackBuffer, synackBuffer.size)

        while (gotTimeToConnect(timestamp) && !messagesHandler.connected) {
            try {
                sendMessage(syn, remote)
                messagesHandler.state = UDPStreamState.SYN_SENT
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

            message as? SynAckMessage ?: continue

            val sendAck = messagesHandler.handleMessage(message)
            if(sendAck) {
                sendMessage(messagesHandler.currentAckMessage(), remote)
            }
        }

        if (!messagesHandler.connected) {
            throw UDPStreamSocketTimeoutException("connect")
        }
    }

    private fun gotTimeToConnect(timestamp: Long): Boolean {
        return (System.currentTimeMillis() - timestamp) < TIME_TO_CONNECT_MS
    }
}