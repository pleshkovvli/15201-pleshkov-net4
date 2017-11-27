//package ru.nsu.ccfit.pleshkov.net4.sockets
//
//import ru.nsu.ccfit.pleshkov.net4.MessagesHandler
//import ru.nsu.ccfit.pleshkov.net4.messages.*
//import java.io.Closeable
//import java.net.*
//import java.util.*
//import java.util.concurrent.ArrayBlockingQueue
//
//open class UDPStreamSocketException(message: String) : Exception(message)
//
//class UDPStreamSocketTimeoutException(reason: String)
//    : UDPStreamSocketException("Time to $reason exceeded")
//
//
//const val INIT_ACK = -1
//
//const val TIME_TO_CONNECT_MS = 10000
//
//
//class UDPStreamSocket : Closeable {
//    private val handler: UDPStreamRoutineHandler
//    internal val udpSocket: DatagramSocket
//
//    constructor(udpSocket: DatagramSocket) {
//        this.udpSocket = udpSocket.apply { soTimeout = TIMEOUT_MS }
//        handler = ClientRoutineHandler(this)
//    }
//
//    constructor(udpSocket: DatagramSocket, handler: UDPStreamRoutineHandler, remote: SocketAddress) {
//        this.udpSocket = udpSocket.apply { soTimeout = TIMEOUT_MS }
//        this.handler = handler
//        this.remoteAddress = remote as InetSocketAddress
//    }
//
//    private val recvBuffer = RecvRingBuffer(DEFAULT_BUFFER_SIZE)
//    private val sendBuffer = SendRingBuffer(DEFAULT_BUFFER_SIZE)
//
//    internal val serviceMessages = ArrayBlockingQueue<Message>(50)
//
//    private var seqNumber: Int = Random().nextInt()
//    private var otherAck: Int = INIT_ACK
//
//    private var ackNumber: Int = INIT_ACK
//
//    internal var ackTimeStamp: Long = System.currentTimeMillis()
//        private set
//
//    private var state: UDPStreamState = UDPStreamState.NOT_CONNECTED
//
//    private var remoteAddress: InetSocketAddress? = null
//
//    private val closeLock = java.lang.Object()
//
//    override fun close() = synchronized(closeLock) {
//        val fin = FinMessage(++seqNumber, ackNumber)
//        state = UDPStreamState.FIN_WAIT
//        offerToSend(fin)
//
//        while (state != UDPStreamState.TIME_ACK) {
//            closeLock.wait()
//        }
//
//        handler.finish()
//    }
//
//    fun bind(address: InetSocketAddress) {
//        udpSocket.bind(address)
//    }
//
//    fun connect(address: InetSocketAddress) {
//        if (state != UDPStreamState.NOT_CONNECTED) {
//            throw UDPStreamSocketException("Socket already connected")
//        }
//
//        remoteAddress = address
//
//        connectAction()
//
//        handler.start()
//    }
//
//    internal fun connectAction() {
//        val timestamp = System.currentTimeMillis()
//
//        //udpSocket.connect(remoteAddress)
//
//        val syn = SynMessage(seqNumber)
//
//        val synackBuffer = ByteArray(SERVICE_BUFFER_SIZE)
//        val synack = DatagramPacket(synackBuffer, synackBuffer.size)
//
//        while (gotTimeToConnect(timestamp) && (state != UDPStreamState.CONNECTED)) {
//            try {
//                sendMessage(syn)
//                state = UDPStreamState.SYN_SENT
//            } catch (e: SocketTimeoutException) {
//                continue
//            }
//
//            try {
//                println(udpSocket.localSocketAddress)
//                udpSocket.receive(synack)
//            } catch (e: SocketTimeoutException) {
//                println("CONNECTING: TIMEOUT")
//                continue
//            }
//
//            val message = try {
//                synackBuffer.toMessage()
//            } catch (e: BadBytesException) {
//                println(e.message)
//                continue
//            }
//
//            if (message is SynAckMessage) {
//                val ack = MessagesHandler().handleSynack(message) ?: continue
//                while (gotTimeToConnect(timestamp)) {
//                    try {
//                        sendMessage(ack)
//                        break
//                    } catch (e: SocketTimeoutException) {
//                        continue
//                    }
//                }
//            }
//        }
//
//        if (state != UDPStreamState.CONNECTED) {
//            throw UDPStreamSocketTimeoutException("connect")
//        }
//    }
//
//    private fun gotTimeToConnect(timestamp: Long): Boolean {
//        return (System.currentTimeMillis() - timestamp) < TIME_TO_CONNECT_MS
//    }
//
//    fun sendMessage(message: Message) {
//        remoteAddress?.let {
//            println("SENDED")
//            udpSocket.send(message.toPacket(it))
//        }
//    }
//
//    fun offerToSend(message: Message) {
//        remoteAddress?.let {
//            println("SENDED")
//            serviceMessages.offer(message)
//
//            handler.notifySended(this)
//        }
//    }
//
//    fun send(buf: ByteArray, offset: Int, length: Int): Int {
//        val write = sendBuffer.write(buf, offset, length)
//        handler.notifySended(this)
//        return write
//    }
//
//    fun recv(buf: ByteArray, offset: Int, length: Int): Int {
//        println("RECV")
//        return recvBuffer.read(buf, offset, length)
//    }
//
//    fun sendAgain() {
//        sendBuffer.dropBufferOffset()
//        handler.notifySended(this)
//    }
//
//    private fun renewAck(message: Message) {
//        if (message.ackNumber > otherAck) {
//            ackTimeStamp = System.currentTimeMillis()
//            sendBuffer.confirmRead(message.ackNumber - otherAck)
//            otherAck = message.ackNumber
//        }
//    }
//
//    internal fun checkFin() {
//        if (state == UDPStreamState.FIN_WAIT && otherAck <= seqNumber) {
//            val fin = FinMessage(seqNumber, ackNumber)
//            sendMessage(fin)
//        }
//
//        if (state == UDPStreamState.CLOSE_WAIT && sendBuffer.allBytesSent) {
//            val fin = FinMessage(seqNumber, ackNumber)
//            sendMessage(fin)
//            state = UDPStreamState.LAST_ACK
//        }
//    }
//
//    internal fun checkService() {
//        var message: Message? = serviceMessages.poll()
//        while (message != null) {
//            sendMessage(message)
//            message = serviceMessages.poll()
//        }
//    }
//
//    internal fun handleData() {
//        if (sendBuffer.availableBytes == 0) {
//            return
//        }
//
//        val bytes = ByteArray(MAX_PAYLOAD_SIZE)
//
//        val read = sendBuffer.read(bytes, 0, MAX_PAYLOAD_SIZE)
//        if (read < 0) {
//            println("!!!! $read")
//        }
//        val dataMessage = DataMessage(seqNumber, ackNumber, ByteArray(read) { i -> bytes[i] })
//
//        do {
//            try {
//                sendMessage(dataMessage)
//                break
//            } catch (e: SocketTimeoutException) {
//            }
//        } while (true)
//    }
//
//    internal fun handleMessage(message: Message) = when (message.type) {
//        MessageType.SYN -> {
//            handleSyn(message as SynMessage)
//        }
//        MessageType.ACK -> {
//            if (state == UDPStreamState.SYN_ACK_SENT) {
//                state = UDPStreamState.CONNECTED
//            }
//            renewAck(message)
//        }
//        MessageType.DATA -> run {
//            handleDataMessage(message)
//        }
//        MessageType.FIN -> {
//            renewAck(message)
//
//            handleFin()
//
//            val ack = AckMessage(seqNumber, ++ackNumber)
//            offerToSend(ack)
//        }
//    }
//
//    private fun handleFin() {
//        if (state == UDPStreamState.CONNECTED) {
//            state = UDPStreamState.CLOSE_WAIT
//        }
//
//        if (state == UDPStreamState.FIN_WAIT) {
//            state = UDPStreamState.TIME_ACK
//        }
//    }
//
//    private fun handleDataMessage(message: Message) {
//        renewAck(message)
//
//        if (message.seqNumber < ackNumber) {
//            val ack = AckMessage(seqNumber, ackNumber)
//            offerToSend(ack)
//            //return
//        }
//
//        if (message.seqNumber > ackNumber) {
//            //return
//        }
//
//        val data = message.data ?: ByteArray(2)
//
//        println("WRITTEN")
//        val written = recvBuffer.write(data, 0, data.size)
//        ackNumber += written
//
//        val ack = AckMessage(seqNumber, ackNumber)
//        offerToSend(ack)
//    }
//
//    private fun handleSyn(message: SynMessage) {
//        if (state == UDPStreamState.NOT_CONNECTED) {
//            ackNumber = message.seqNumber + 1
//            val synackMessage = SynAckMessage(seqNumber, ackNumber)
//            state = UDPStreamState.SYN_ACK_SENT
//            offerToSend(synackMessage)
//        } else {
//            val ack = AckMessage(seqNumber, ackNumber)
//            offerToSend(ack)
//        }
//    }
//}
