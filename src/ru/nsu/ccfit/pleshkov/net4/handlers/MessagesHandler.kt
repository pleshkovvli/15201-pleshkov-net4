package ru.nsu.ccfit.pleshkov.net4.handlers

import ru.nsu.ccfit.pleshkov.net4.messages.*
import ru.nsu.ccfit.pleshkov.net4.buffers.RecvRingBuffer
import ru.nsu.ccfit.pleshkov.net4.buffers.SendRingBuffer
import java.util.*
import java.util.concurrent.ArrayBlockingQueue

const val INIT_ACK = -1

class MessagesHandler(
        recvBufferSize : Int = DEFAULT_BUFFER_SIZE,
        sendBufferSize: Int = DEFAULT_BUFFER_SIZE
) {
    val connected: Boolean
        get() = (state == UDPStreamState.CONNECTED)
    val notConnected
        get() = (state == UDPStreamState.NOT_CONNECTED)

    val available
        get() = recvBuffer.available

    private val stateLock = java.lang.Object()

    private val serviceMessages = ArrayBlockingQueue<Message>(50)

    private val recvBuffer = RecvRingBuffer(recvBufferSize)
    private val sendBuffer = SendRingBuffer(sendBufferSize)

    private var state: UDPStreamState = UDPStreamState.NOT_CONNECTED

    private var seqNumber: Int = Random().nextInt()
    private var otherAck: Int = INIT_ACK
    private var ackTimeStamp: Long = System.currentTimeMillis()

    private var ackNumber: Int = INIT_ACK

    fun send(buf: ByteArray, offset: Int, length: Int) = sendBuffer.write(buf, offset, length)
    fun recv(buf: ByteArray, offset: Int, length: Int) = recvBuffer.read(buf, offset, length)

    fun initSynMessage(): SynMessage {
        state = UDPStreamState.SYN_SENT
        return SynMessage(seqNumber)
    }

    fun currentServiceMessage(): Message? = serviceMessages.poll()

    fun currentDataMessage(): DataMessage? = synchronized(stateLock) {
        if (sendBuffer.available == 0) {
            return null
        }

        //println("AVAILABLE ${sendBuffer.availableBytes}")

        val bytes = ByteArray(MAX_PAYLOAD_SIZE)
        val read = sendBuffer.read(bytes, 0, MAX_PAYLOAD_SIZE)

        //println("AVAILABLE NOW ${sendBuffer.availableBytes}")

        val dataMessage = DataMessage(seqNumber, ackNumber, bytes, read)
        seqNumber += read

        //println("DATA $read")

        stateLock.notifyAll()

        return dataMessage
    }

    fun handleMessage(message: Message): Boolean = when (message) {
        is SynMessage -> handleSyn(message)
        is SynAckMessage -> handleSynack(message)
        is AckMessage -> handleAck(message)
        is DataMessage -> handleDataMessage(message)
        is FinMessage -> handleFin()
    }

    fun checkResend(): Boolean {
        if (System.currentTimeMillis() - ackTimeStamp < TIMEOUT_MS) {
            return false
        }

        ackTimeStamp = System.currentTimeMillis()
        val offset = sendBuffer.dropBufferOffset()
        seqNumber -= offset
        checkFin()
        return true
    }

    fun fin(): Boolean = synchronized(stateLock) {
        while (!sendBuffer.allBytesSent) {
            stateLock.wait()
        }

        if (state != UDPStreamState.CONNECTED) {
            return false
        }

        val fin = currentFinMessage()
        state = UDPStreamState.FIN_WAIT
        serviceMessages.put(fin)

        while (state != UDPStreamState.TIME_ACK && state != UDPStreamState.CLOSED) {
            stateLock.wait()
        }

        return true
    }

    fun closeBuffers() {
        recvBuffer.close()
        sendBuffer.close()
    }

    fun closed() = when (state) {
        UDPStreamState.CLOSED -> true
        UDPStreamState.TIME_ACK -> timeToClose()
        else -> false
    }

    private fun handleSyn(message: SynMessage): Boolean {
        if (state == UDPStreamState.NOT_CONNECTED) {
            ackNumber = message.seqNumber + 1
            val synackMessage = SynAckMessage(seqNumber, ackNumber)
            state = UDPStreamState.SYN_ACK_SENT
            return serviceMessages.add(synackMessage)
        }

        return serviceMessages.offer(currentAckMessage())
    }

    private fun handleSynack(message: SynAckMessage): Boolean {
        if (message.ackNumber != (seqNumber + 1)) {
            return false
        }

        ++seqNumber

        ackNumber = message.seqNumber + 1
        otherAck = message.ackNumber

        state = UDPStreamState.CONNECTED

        return serviceMessages.add(currentAckMessage())
    }

    private fun handleAck(message: AckMessage): Boolean = synchronized(stateLock) {
        if (state == UDPStreamState.SYN_ACK_SENT) {
            state = UDPStreamState.CONNECTED

            ++seqNumber

            renewAck(message)

            stateLock.notifyAll()

            return false
        }

        //println("ACK ${message.ackNumber} OTHER ACK $otherAck SEQ $seqNumber")

        if (message.ackNumber < otherAck) {
            return false
        }

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
        //println("CONFIRMED ACK ${message.ackNumber - otherAck}")
        renewAck(message)
        stateLock.notifyAll()

        return false
    }

    private fun handleDataMessage(message: DataMessage): Boolean = synchronized(stateLock) {
        //println("MESSAGE ${message.seqNumber} ACK $ackNumber")

        if (message.seqNumber < ackNumber) {
            return serviceMessages.offer(currentAckMessage())
        }

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

    private fun handleFin(): Boolean = synchronized(stateLock) {
        state = when (state) {
            UDPStreamState.SYN_ACK_SENT -> UDPStreamState.CLOSE_WAIT
            UDPStreamState.CONNECTED -> UDPStreamState.CLOSE_WAIT
            UDPStreamState.FIN_WAIT -> UDPStreamState.TIME_ACK
            UDPStreamState.FIN_WAIT_ACK -> UDPStreamState.TIME_ACK
            else -> state
        }

        ++ackNumber

        stateLock.notifyAll()

        return serviceMessages.offer(currentAckMessage())
    }


    private fun checkFin() = synchronized(stateLock) {
        if (state == UDPStreamState.FIN_WAIT || state == UDPStreamState.LAST_ACK) {
            serviceMessages.offer(currentFinMessage())
        }

        if (state == UDPStreamState.CLOSE_WAIT && sendBuffer.allBytesSent) {
            state = UDPStreamState.LAST_ACK
            stateLock.notifyAll()
            serviceMessages.offer(currentFinMessage())
        }
    }

    private fun currentAckMessage() = AckMessage(seqNumber, ackNumber)
    private fun currentFinMessage() = FinMessage(seqNumber, ackNumber)

    private fun renewAck(message: Message) {
        otherAck = message.ackNumber
        ackTimeStamp = System.currentTimeMillis()
    }

    private fun timeToClose(): Boolean {
        return (System.currentTimeMillis() - ackTimeStamp) > 4 * TIMEOUT_MS
    }
}
