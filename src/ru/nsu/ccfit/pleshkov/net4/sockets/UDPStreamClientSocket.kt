package ru.nsu.ccfit.pleshkov.net4.sockets

//import ru.nsu.ccfit.pleshkov.net4.messages.*
//import java.io.*
//import java.net.*
//import java.util.*
//import kotlin.concurrent.thread
//
//class UDPStreamClientSocket : Closeable {
//
//    private val udpSocket = DatagramSocket().apply { soTimeout = TIMEOUT_MS }
//    private val recvBuffer = ReceivingRingBuffer(DEFAULT_BUFFER_SIZE)
//    private val sendBuffer = SendingRingBuffer(DEFAULT_BUFFER_SIZE)
//
//    private var seqNumber: Int = Random().nextInt()
//    private var otherAck: Int = INIT_ACK
//
//    private var ackNumber: Int = INIT_ACK
//
//    private var ackTimeStamp: Long = System.currentTimeMillis()
//
//    private var state: UDPStreamState = UDPStreamState.NOT_CONNECTED
//
//    fun bind(address: InetSocketAddress) {
//        udpSocket.bind(address)
//    }
//
//    fun connect(address: InetSocketAddress) {
//        val timestamp = System.currentTimeMillis()
//
//        udpSocket.connect(address)
//
//        val syn = SynMessage(seqNumber)
//
//        val synackBuffer = ByteArray(SERVICE_BUFFER_SIZE)
//        val synack = DatagramPacket(synackBuffer, synackBuffer.size)
//
//        while (gotTimeToConnect(timestamp) && (state == UDPStreamState.NOT_CONNECTED)) {
//            try {
//                udpSocket.sendMessage(syn)
//                state = UDPStreamState.SYN_SENT
//            } catch (e: SocketTimeoutException) {
//                continue
//            }
//
//            try {
//                udpSocket.receive(synack)
//            } catch (e: SocketTimeoutException) {
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
//            if (message.type == MessageType.SYN && message.ackNumber == (seqNumber + 1)) {
//                ++seqNumber
//
//                ackNumber = message.seqNumber + 1
//                otherAck = message.ackNumber
//
//                state = UDPStreamState.CONNECTED
//
//                val ack = AckMessage(seqNumber, ackNumber)
//                while (gotTimeToConnect(timestamp)) {
//                    try {
//                        udpSocket.sendMessage(ack)
//                        break
//                    } catch (e: SocketTimeoutException) {
//                        continue
//                    }
//                }
//            }
//        }
//
//        if (state == UDPStreamState.NOT_CONNECTED) {
//            throw UDPStreamSocketTimeoutException("connect")
//        }
//
//        thread(name = "Sending") {
//            while (!Thread.interrupted()) {
//                sendingRoutine()
//            }
//        }.start()
//
//        thread(name = "Receiving") {
//            while (!Thread.interrupted()) {
//                receivingRoutine()
//            }
//        }.start()
//    }
//
//    private fun gotTimeToConnect(timestamp: Long): Boolean {
//        return (System.currentTimeMillis() - timestamp) < TIME_TO_CONNECT_MS
//    }
//
//    fun send(buf: ByteArray, offset: Int, length: Int): Int {
//        return sendBuffer.write(buf, offset, length)
//    }
//
//    fun recv(buf: ByteArray, offset: Int, length: Int): Int {
//        return recvBuffer.read(buf, offset, length)
//    }
//
//    override fun close() {
//        val fin = FinMessage(++seqNumber, ackNumber)
//        udpSocket.sendMessage(fin)
//
//        state = UDPStreamState.FIN_WAIT
//    }
//
//    private fun sendingRoutine() {
//        val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
//
//        if (state == UDPStreamState.FIN_WAIT && otherAck <= seqNumber) {
//            val fin = FinMessage(seqNumber, ackNumber)
//            udpSocket.sendMessage(fin)
//        }
//
//        if (state == UDPStreamState.CLOSE_WAIT && sendBuffer.allBytesSent) {
//            val fin = FinMessage(seqNumber, ackNumber)
//            udpSocket.sendMessage(fin)
//            state = UDPStreamState.LAST_ACK
//        }
//
//        handleData(bytes)
//
//    }
//
//    private fun handleData(bytes: ByteArray) {
//        val read = sendBuffer.read(bytes, 0, DEFAULT_BUFFER_SIZE)
//        val dataMessage = DataMessage(seqNumber, ackNumber, ByteArray(read) { i -> bytes[i] })
//
//        do {
//            try {
//                udpSocket.sendMessage(dataMessage)
//                break
//            } catch (e: SocketTimeoutException) {
//            }
//        } while (true)
//    }
//
//    private fun receivingRoutine() {
//        if (System.currentTimeMillis() - ackTimeStamp > 1000) {
//            sendBuffer.dropBufferOffset()
//        }
//
//        val bytes = ByteArray(MESSAGE_BUFFER_SIZE)
//        val packet = DatagramPacket(bytes, bytes.size)
//
//        try {
//            udpSocket.receive(packet)
//        } catch (e: SocketTimeoutException) {
//            return
//        }
//
//        val message = try {
//            bytes.toMessage()
//        } catch (e: BadBytesException) {
//            println(e.message)
//            return
//        }
//
//        handleMessage(message)
//    }
//
//    private fun handleMessage(message: Message) = when (message.type) {
//        MessageType.SYN -> {
//            val ack = AckMessage(seqNumber, ackNumber)
//            udpSocket.sendMessage(ack)
//        }
//        MessageType.ACK -> {
//            renewAck(message)
//        }
//        MessageType.DATA -> run {
//            renewAck(message)
//
//            if (message.seqNumber < ackNumber) {
//                val ack = AckMessage(seqNumber, ackNumber)
//                udpSocket.sendMessage(ack)
//                return
//            }
//
//            if (message.seqNumber > ackNumber) {
//                return
//            }
//
//            val data = message.data ?: return
//
//            val written = recvBuffer.write(data, 0, data.size)
//            ackNumber += written
//
//            val ack = AckMessage(seqNumber, ackNumber)
//            udpSocket.sendMessage(ack)
//        }
//        MessageType.FIN -> {
//            renewAck(message)
//
//            if (state == UDPStreamState.CONNECTED) {
//                state = UDPStreamState.CLOSE_WAIT
//            }
//            if (state == UDPStreamState.FIN_WAIT) {
//                state = UDPStreamState.TIME_ACK
//            }
//
//            val ack = AckMessage(seqNumber, ++ackNumber)
//            try {
//                udpSocket.sendMessage(ack)
//            } catch (e: SocketTimeoutException) {
//            }
//        }
//
//    }
//
//    private fun renewAck(message: Message) {
//        if (message.ackNumber > otherAck) {
//            ackTimeStamp = System.currentTimeMillis()
//            sendBuffer.confirmRead(message.ackNumber - otherAck)
//            otherAck = message.ackNumber
//        }
//    }
//}
//
//fun DatagramSocket.sendMessage(message: Message) {
//    send(message.toPacket(remoteSocketAddress))
//}