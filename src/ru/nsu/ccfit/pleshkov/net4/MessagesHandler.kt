package ru.nsu.ccfit.pleshkov.net4

import ru.nsu.ccfit.pleshkov.net4.messages.*
import ru.nsu.ccfit.pleshkov.net4.sockets.INIT_ACK
import ru.nsu.ccfit.pleshkov.net4.sockets.RecvRingBuffer
import ru.nsu.ccfit.pleshkov.net4.sockets.SendRingBuffer
import ru.nsu.ccfit.pleshkov.net4.sockets.TIMEOUT_MS
import java.util.*

class MessagesHandler {
    private val recvBuffer = RecvRingBuffer(DEFAULT_BUFFER_SIZE)
    private val sendBuffer = SendRingBuffer(DEFAULT_BUFFER_SIZE)

    private var seqNumber: Int = Random().nextInt()
    private var otherAck: Int = INIT_ACK

    private var ackNumber: Int = INIT_ACK

    private var ackTimeStamp: Long = System.currentTimeMillis()

    internal var state: UDPStreamState = UDPStreamState.NOT_CONNECTED

    val connected: Boolean
        get() = (state == UDPStreamState.CONNECTED)

    fun initSynMessage() = SynMessage(seqNumber)

    fun currentAckMessage() = AckMessage(seqNumber, ackNumber)

    fun send(buf: ByteArray, offset: Int, length: Int) = sendBuffer.write(buf, offset, length)
    fun recv(buf: ByteArray, offset: Int, length: Int) = recvBuffer.read(buf, offset, length)

    fun currentDataMessage() : DataMessage? {
        if (sendBuffer.availableBytes == 0) {
            return null
        }

        val bytes = ByteArray(MAX_PAYLOAD_SIZE)
        val read = sendBuffer.read(bytes, 0, MAX_PAYLOAD_SIZE)

        val dataMessage = DataMessage(seqNumber, ackNumber, ByteArray(read) { i -> bytes[i] })
        seqNumber += read
        return dataMessage
    }

    fun currentFinMessage() = FinMessage(seqNumber, ackNumber)

    fun checkFin() : FinMessage? {
        if (state == UDPStreamState.FIN_WAIT && otherAck <= seqNumber) {
            return FinMessage(seqNumber, ackNumber)
        }

        if (state == UDPStreamState.CLOSE_WAIT && sendBuffer.allBytesSent) {
            state = UDPStreamState.LAST_ACK
            return FinMessage(seqNumber, ackNumber)
        }

        return null
    }

    fun handleMessage(message: Message) : Message {
        var ack: Message? = null
        when(message) {
            is SynMessage -> {
                ack = handleSyn(message)
            }
            is AckMessage -> handleAck(message)
            is DataMessage -> handleDataMessage(message)
            is FinMessage -> handleFin()
        }

        renewAck(message)

        return ack ?: AckMessage(seqNumber, ackNumber)
    }

    fun handleSynack(message: SynAckMessage): AckMessage? {
        if (message.ackNumber != (seqNumber + 1)) {
            return null
        }

        ++seqNumber

        ackNumber = message.seqNumber + 1
        otherAck = message.ackNumber

        state = UDPStreamState.CONNECTED

        return AckMessage(seqNumber, ackNumber)
    }

    fun handleSyn(message: SynMessage): Message {
        if (state == UDPStreamState.NOT_CONNECTED) {
            ackNumber = message.seqNumber + 1
            val synackMessage = SynAckMessage(seqNumber, ackNumber)
            state = UDPStreamState.SYN_ACK_SENT
            return synackMessage
        }

        return AckMessage(seqNumber, ackNumber)
    }

    fun handleAck(message: AckMessage) {
        if (message.ackNumber <= otherAck) {
            return
        }

        ackTimeStamp = System.currentTimeMillis()
        sendBuffer.confirmRead(message.ackNumber - otherAck)
        otherAck = message.ackNumber

    }

    fun handleDataMessage(message: DataMessage) {
        if (message.seqNumber != ackNumber) {
            return
        }

        val data = message.data ?: throw Exception()
        val written = recvBuffer.write(data, 0, data.size)
        ackNumber += written
    }

    fun handleFin() {
        if (state == UDPStreamState.CONNECTED) {
            state = UDPStreamState.CLOSE_WAIT
        }

        if (state == UDPStreamState.FIN_WAIT) {
            state = UDPStreamState.TIME_ACK
        }
    }

    fun checkResend() {
        if (System.currentTimeMillis() - ackTimeStamp > TIMEOUT_MS) {
            val offset = sendBuffer.dropBufferOffset()
            seqNumber -= offset
        }
    }

    private fun renewAck(message: Message) {
        if (message.ackNumber > otherAck) {
            ackTimeStamp = System.currentTimeMillis()
            sendBuffer.confirmRead(message.ackNumber - otherAck)
            otherAck = message.ackNumber
        }
    }
}