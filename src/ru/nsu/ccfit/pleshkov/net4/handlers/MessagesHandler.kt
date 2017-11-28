package ru.nsu.ccfit.pleshkov.net4.handlers

import ru.nsu.ccfit.pleshkov.net4.messages.*
import ru.nsu.ccfit.pleshkov.net4.buffers.RecvRingBuffer
import ru.nsu.ccfit.pleshkov.net4.buffers.SendRingBuffer
import java.util.*
import java.util.concurrent.ArrayBlockingQueue

const val INIT_ACK = -1

class MessagesHandler {
    private val recvBuffer = RecvRingBuffer(DEFAULT_BUFFER_SIZE)
    private val sendBuffer = SendRingBuffer(DEFAULT_BUFFER_SIZE)

    private var seqNumber: Int = Random().nextInt()
    private var otherAck: Int = INIT_ACK

    private var ackNumber: Int = INIT_ACK

    var ackTimeStamp: Long = System.currentTimeMillis()
        private set

    internal var state: UDPStreamState = UDPStreamState.NOT_CONNECTED

    private val stateLock = java.lang.Object()

    val connected: Boolean
        get() = (state == UDPStreamState.CONNECTED)

    private val serviceMessages = ArrayBlockingQueue<Message>(50)
    val available
        get() = recvBuffer.availableBytes

    fun closeBuffers() {
        recvBuffer.closing = true
        sendBuffer.closing = true
    }

    fun closed() = when (state) {
        UDPStreamState.CLOSED -> true
        UDPStreamState.TIME_ACK -> timeToClose()
        else -> false
    }

    private fun timeToClose(): Boolean {
        return (System.currentTimeMillis() - ackTimeStamp) > 2 * TIMEOUT_MS
    }

    fun initSynMessage() = SynMessage(seqNumber)

    fun currentServiceMessage(): Message? = serviceMessages.poll()
    fun offerServiceMessage(message: Message) = serviceMessages.offer(message)

    fun currentAckMessage() = AckMessage(seqNumber, ackNumber)

    fun send(buf: ByteArray, offset: Int, length: Int) = sendBuffer.write(buf, offset, length)
    fun recv(buf: ByteArray, offset: Int, length: Int) = recvBuffer.read(buf, offset, length)

    fun currentDataMessage(): DataMessage? {
        if (!sendBuffer.dataAvailable) {
            return null
        }

        val bytes = ByteArray(MAX_PAYLOAD_SIZE)
        val read = sendBuffer.read(bytes, 0, MAX_PAYLOAD_SIZE)

        val dataMessage = DataMessage(seqNumber, ackNumber, bytes, read)
        seqNumber += read

        synchronized(stateLock) {
            stateLock.notifyAll()
        }

        return dataMessage
    }

    fun currentFinMessage() = FinMessage(seqNumber, ackNumber)

    fun waitTimeAck() = synchronized(stateLock) {
        while (state != UDPStreamState.TIME_ACK && state != UDPStreamState.CLOSED) {
            stateLock.wait()
        }
    }

    fun fin(): Boolean = synchronized(stateLock) {
        //println("WAITING FOR SEND")
        while (!allSent()) {
            stateLock.wait()
        }

        if (state != UDPStreamState.CONNECTED) {
            return false
        }

        val fin = currentFinMessage()
        state = UDPStreamState.FIN_WAIT
        serviceMessages.put(fin)


        //println("WAITING TIME ACK")
        while (state != UDPStreamState.TIME_ACK && state != UDPStreamState.CLOSED) {
            stateLock.wait()
        }


        //println("TIME ACK GOT")

        return true
    }

    fun checkFin(): FinMessage? = synchronized(stateLock) {
        if (state == UDPStreamState.FIN_WAIT || state == UDPStreamState.LAST_ACK) {
            if (System.currentTimeMillis() - ackTimeStamp > TIMEOUT_MS) {
                ackTimeStamp = System.currentTimeMillis()
                return FinMessage(seqNumber, ackNumber)
            }

            return null
        }

        if (state == UDPStreamState.CLOSE_WAIT && allSent()) {
            state = UDPStreamState.LAST_ACK
            stateLock.notifyAll()
            return FinMessage(seqNumber, ackNumber)
        }

        return null
    }

    private fun allSent() = sendBuffer.allBytesSent

    fun handleMessage(message: Message): Boolean = when (message) {
        is SynMessage -> handleSyn(message)
        is SynAckMessage -> handleSynack(message)
        is AckMessage -> handleAck(message)
        is DataMessage -> handleDataMessage(message)
        is FinMessage -> handleFin(message)
    }

    fun handleSyn(message: SynMessage): Boolean {
        if (state == UDPStreamState.NOT_CONNECTED) {
            ackNumber = message.seqNumber + 1
            val synackMessage = SynAckMessage(seqNumber, ackNumber)
            state = UDPStreamState.SYN_ACK_SENT
            return serviceMessages.add(synackMessage)
        }

        return serviceMessages.offer(currentAckMessage())
    }

    fun handleSynack(message: SynAckMessage): Boolean {
        if (message.ackNumber != (seqNumber + 1)) {
            return false
        }

        ++seqNumber

        ackNumber = message.seqNumber + 1
        otherAck = message.ackNumber

        state = UDPStreamState.CONNECTED

        return serviceMessages.add(currentAckMessage())
    }

    fun handleAck(message: AckMessage): Boolean = synchronized(stateLock) {
        if (state == UDPStreamState.SYN_ACK_SENT) {
            state = UDPStreamState.CONNECTED

            renewAck(message)

            stateLock.notifyAll()

            return false
        }

        if (message.ackNumber <= otherAck) {
            return false
        }

        renewAck(message)

        if (message.ackNumber > seqNumber) {
            when (state) {
                UDPStreamState.LAST_ACK -> state = UDPStreamState.CLOSED
                UDPStreamState.FIN_WAIT -> state = UDPStreamState.FIN_WAIT_ACK
                else -> {
                }
            }

            stateLock.notifyAll()
            return false
        }

        sendBuffer.confirmRead(message.ackNumber - otherAck)
        stateLock.notifyAll()   ///??????

        return false
    }

    fun handleDataMessage(message: DataMessage): Boolean {
        if (message.seqNumber != ackNumber) {
            return false
        }

        val data = message.data ?: throw UDPStreamSocketException("Data message without data")
        val written = recvBuffer.write(data, 0, data.size)
        ackNumber += written

        if (message.ackNumber > otherAck) {
            renewAck(message)
        }

        return serviceMessages.offer(currentAckMessage())
    }

    fun handleFin(message: FinMessage): Boolean = synchronized(stateLock) {
        state = when (state) {
            UDPStreamState.SYN_ACK_SENT -> UDPStreamState.CLOSE_WAIT
            UDPStreamState.CONNECTED -> UDPStreamState.CLOSE_WAIT
            UDPStreamState.FIN_WAIT -> UDPStreamState.TIME_ACK
            UDPStreamState.FIN_WAIT_ACK -> UDPStreamState.TIME_ACK
            else -> state
        }

        stateLock.notifyAll()

        return serviceMessages.offer(currentAckMessage())
    }

    fun checkResend() {
        if (System.currentTimeMillis() - ackTimeStamp > TIMEOUT_MS) {
            ackTimeStamp = System.currentTimeMillis()
            val offset = sendBuffer.dropBufferOffset()
            seqNumber -= offset
            checkFin()
        }
    }

    private fun renewAck(message: Message) {
        otherAck = message.ackNumber
        ackTimeStamp = System.currentTimeMillis()
    }
}