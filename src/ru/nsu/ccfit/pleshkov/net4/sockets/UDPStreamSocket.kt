package ru.nsu.ccfit.pleshkov.net4.sockets

import ru.nsu.ccfit.pleshkov.net4.messages.*
import java.io.Closeable
import java.net.*
import java.util.*

open class UDPStreamSocketException(message: String) : Exception(message)

class UDPStreamSocketTimeoutException(reason: String)
    : UDPStreamSocketException("Time to $reason exceeded")


const val INIT_ACK = -1

const val TIME_TO_CONNECT_MS = 5000


class UDPStreamSocket : Closeable {

    private val handler: UDPStreamRoutineHandler
    internal val udpSocket: DatagramSocket

    constructor(udpSocket: DatagramSocket) {
        this.udpSocket = udpSocket.apply { soTimeout = TIMEOUT_MS }
        handler = ClientRoutineHandler(this)
    }

    constructor(udpSocket: DatagramSocket, handler: UDPStreamRoutineHandler) {
        this.udpSocket = udpSocket.apply { soTimeout = TIMEOUT_MS }
        this.handler = handler
    }

    private val recvBuffer = RecvRingBuffer(DEFAULT_BUFFER_SIZE)
    private val sendBuffer = SendRingBuffer(DEFAULT_BUFFER_SIZE)

    private var seqNumber: Int = Random().nextInt()
    private var otherAck: Int = INIT_ACK

    private var ackNumber: Int = INIT_ACK

    internal var ackTimeStamp: Long = System.currentTimeMillis()
        private set

    private var state: UDPStreamState = UDPStreamState.NOT_CONNECTED

    private var remoteAddress: InetSocketAddress? = null

    override fun close() {
        val fin = FinMessage(++seqNumber, ackNumber)
        sendMessage(fin)

        state = UDPStreamState.FIN_WAIT
    }

    fun bind(address: InetSocketAddress) {
        udpSocket.bind(address)
    }

    fun connect(address: InetSocketAddress) {
        if(state != UDPStreamState.NOT_CONNECTED) {
            throw UDPStreamSocketException("Socket already connected")
        }

        remoteAddress = address

        handler.start()
    }

    internal fun connectAction() {
        val timestamp = System.currentTimeMillis()

        udpSocket.connect(remoteAddress)

        val syn = SynMessage(seqNumber)

        val synackBuffer = ByteArray(SERVICE_BUFFER_SIZE)
        val synack = DatagramPacket(synackBuffer, synackBuffer.size)

        while (gotTimeToConnect(timestamp) && (state == UDPStreamState.NOT_CONNECTED)) {
            try {
                sendMessage(syn)
                state = UDPStreamState.SYN_SENT
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
                println(e.message)
                continue
            }

            if (message.type == MessageType.SYN && message.ackNumber == (seqNumber + 1)) {
                ++seqNumber

                ackNumber = message.seqNumber + 1
                otherAck = message.ackNumber

                state = UDPStreamState.CONNECTED

                val ack = AckMessage(seqNumber, ackNumber)
                while (gotTimeToConnect(timestamp)) {
                    try {
                        udpSocket.sendMessage(ack)
                        break
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                }
            }
        }

        if (state == UDPStreamState.NOT_CONNECTED) {
            throw UDPStreamSocketTimeoutException("connect")
        }
    }

    private fun gotTimeToConnect(timestamp: Long): Boolean {
        return (System.currentTimeMillis() - timestamp) < TIME_TO_CONNECT_MS
    }

    fun sendMessage(message: Message) {
        remoteAddress?.let {
            println("SENDED")
            udpSocket.send(message.toPacket(it))
        }
    }

    fun send(buf: ByteArray, offset: Int, length: Int): Int {
        val write = sendBuffer.write(buf, offset, length)
        handler.notifySended(this)
        return write
    }

    fun recv(buf: ByteArray, offset: Int, length: Int): Int {
        println("RECV")
        return recvBuffer.read(buf, offset, length)
    }

    fun sendAgain() {
        sendBuffer.dropBufferOffset()
    }

    private fun renewAck(message: Message) {
        if (message.ackNumber > otherAck) {
            ackTimeStamp = System.currentTimeMillis()
            sendBuffer.confirmRead(message.ackNumber - otherAck)
            otherAck = message.ackNumber
        }
    }

    internal fun checkFin() {
        if (state == UDPStreamState.FIN_WAIT && otherAck <= seqNumber) {
            val fin = FinMessage(seqNumber, ackNumber)
            sendMessage(fin)
        }

        if (state == UDPStreamState.CLOSE_WAIT && sendBuffer.allBytesSent) {
            val fin = FinMessage(seqNumber, ackNumber)
            sendMessage(fin)
            state = UDPStreamState.LAST_ACK
        }
    }

    internal fun handleData() {
        val bytes = ByteArray(DEFAULT_BUFFER_SIZE)

        val read = sendBuffer.read(bytes, 0, DEFAULT_BUFFER_SIZE)
        if(read < 0) {
            println("!!!! $read")
        }
        val dataMessage = DataMessage(seqNumber, ackNumber, ByteArray(read) { i -> bytes[i] })

        do {
            try {
                sendMessage(dataMessage)
                break
            } catch (e: SocketTimeoutException) {
            }
        } while (true)
    }

    internal fun handleMessage(message: Message) = when (message.type) {
        MessageType.SYN -> {
            val ack = AckMessage(seqNumber, ackNumber)
            sendMessage(ack)
        }
        MessageType.ACK -> {
            renewAck(message)
        }
        MessageType.DATA -> run {
            renewAck(message)

            println("SEQ=${message.seqNumber} ACK=$ackNumber")

            if (message.seqNumber < ackNumber) {
                val ack = AckMessage(seqNumber, ackNumber)
                sendMessage(ack)
                return
            }

            if (message.seqNumber > ackNumber) {
                return
            }

            val data = message.data ?: return

            println("WRITTEN")
            val written = recvBuffer.write(data, 0, data.size)
            ackNumber += written

            val ack = AckMessage(seqNumber, ackNumber)
            sendMessage(ack)
        }
        MessageType.FIN -> {
            renewAck(message)

            if (state == UDPStreamState.CONNECTED) {
                state = UDPStreamState.CLOSE_WAIT
            }
            if (state == UDPStreamState.FIN_WAIT) {
                state = UDPStreamState.TIME_ACK
            }

            val ack = AckMessage(seqNumber, ++ackNumber)
            try {
                sendMessage(ack)
            } catch (e: SocketTimeoutException) {
            }
        }
    }
}

fun Message.toPacket(address: SocketAddress): DatagramPacket {
    //try {
        val bytes = this.toBytes()
        return DatagramPacket(bytes, bytes.size, address)
//    } catch (e: NegativeArraySizeException) {
//        println("$type $dataLength")
//        throw e
//    }
}