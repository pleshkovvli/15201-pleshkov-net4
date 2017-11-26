package ru.nsu.ccfit.pleshkov.net4

import ru.nsu.ccfit.pleshkov.net4.messages.*
import ru.nsu.ccfit.pleshkov.net4.sockets.*
import java.net.*
import java.util.concurrent.ArrayBlockingQueue

class ClientRoutinesHandler : RoutinesHandler {

    constructor(port: Int? = null) : super() {
        udpSocket = port?.let { DatagramSocket(port) } ?: DatagramSocket()
    }

    constructor(remote: InetSocketAddress, port: Int? = null) : this(port) {
        remoteAddress = remote
        connectAction()
        start()
    }

    private val messagesHandler = MessagesHandler()
    private lateinit var remoteAddress: InetSocketAddress

    private val serviceMessages = ArrayBlockingQueue<Message>(50)

    private val sendingLock = java.lang.Object()
    private var sendRoutineRun: Boolean = false

    private val closeLock = java.lang.Object()

    override val udpSocket: DatagramSocket

    override fun sendingRoutine() {
        waitOnSend()

        checkService()
        handleData()
        checkFin()
    }

    private fun waitOnSend() = synchronized(sendingLock) {
        while (!sendRoutineRun) {
            sendingLock.wait()
        }
        sendRoutineRun = false
    }


    override fun send(id: Int, buf: ByteArray, offset: Int, length: Int) : Int {
        if(messagesHandler.state != UDPStreamState.CONNECTED) {
            throw Exception()
        }

        val sended = messagesHandler.send(buf, offset, length)
        notifySended()
        return sended
    }

    override fun recv(id: Int, buf: ByteArray, offset: Int, length: Int) : Int {
        if(messagesHandler.state != UDPStreamState.CONNECTED) {
            throw Exception()
        }

        return messagesHandler.recv(buf, offset, length)
    }

    override fun receivingRoutine() {
        messagesHandler.checkResend()

        val bytes = ByteArray(MESSAGE_BUFFER_SIZE)
        val packet = DatagramPacket(bytes, bytes.size)

        try {
            udpSocket.receive(packet)
        } catch (e: SocketTimeoutException) {
            if(messagesHandler.state == UDPStreamState.TIME_ACK && timeToClose()) {
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

        val ack = messagesHandler.handleMessage(message)

        val offerSuccess = serviceMessages.offer(ack)
        if (offerSuccess) {
            notifySended()
        }
    }

    private fun timeToClose() = (System.currentTimeMillis() - timeClose) > 2 * TIMEOUT_MS

    fun closeConnection()  {
        val fin = messagesHandler.currentFinMessage()
        messagesHandler.state = UDPStreamState.FIN_WAIT
        serviceMessages.put(fin)

        synchronized(closeLock) {
            while (messagesHandler.state != UDPStreamState.TIME_ACK) {
                closeLock.wait()
            }
        }

        finish()
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

        connectAction()
        start()
    }

    fun connectAction() {
        val timestamp = System.currentTimeMillis()

        //udpSocket.connect(remoteAddress)

        val syn = messagesHandler.initSynMessage()

        val synackBuffer = ByteArray(SERVICE_BUFFER_SIZE)
        val synack = DatagramPacket(synackBuffer, synackBuffer.size)

        while (gotTimeToConnect(timestamp) && !messagesHandler.connected) {
            try {
                sendMessage(syn)
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

            val ack = MessagesHandler().handleSynack(message) ?: continue
            while (gotTimeToConnect(timestamp)) {
                try {
                    sendMessage(ack)
                    break
                } catch (e: SocketTimeoutException) {
                    continue
                }
            }

        }

        if (messagesHandler.state != UDPStreamState.CONNECTED) {
            throw UDPStreamSocketTimeoutException("connect")
        }
    }

    private fun gotTimeToConnect(timestamp: Long): Boolean {
        return (System.currentTimeMillis() - timestamp) < TIME_TO_CONNECT_MS
    }

    private fun checkFin() = synchronized(closeLock) {
        messagesHandler.checkFin()?.let {
            sendBlocking(it)
            closeLock.notifyAll()
        }
    }

    private fun checkService() {
        var message: Message? = serviceMessages.poll()
        while (message != null) {
            sendBlocking(message)
            message = serviceMessages.poll()
        }
    }

    private fun handleData() {
        val dataMessage = messagesHandler.currentDataMessage()
        dataMessage?.let { sendBlocking(it) }
    }

    private fun sendBlocking(dataMessage: Message) {
        while (true) {
            try {
                sendMessage(dataMessage)
                break
            } catch (e: SocketTimeoutException) {
            }
        }
    }

    private fun sendMessage(message: Message) {
        remoteAddress.let { udpSocket.send(message.toPacket(it)) }
    }
}