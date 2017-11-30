package ru.nsu.ccfit.pleshkov.net4.messages

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.SocketAddress
import java.util.*

const val MAX_PAYLOAD_SIZE = 450
const val INT_SIZE = 4
const val SERVICE_BUFFER_SIZE = INT_SIZE * 4
const val MESSAGE_BUFFER_SIZE = SERVICE_BUFFER_SIZE + MAX_PAYLOAD_SIZE

enum class UDPStreamState {
    NOT_CONNECTED,
    LISTENING,
    SYN_SENT,
    SYN_ACK_SENT,
    CONNECTED,
    FIN_WAIT,
    FIN_WAIT_ACK,
    CLOSE_WAIT,
    LAST_ACK,
    TIME_ACK,
    CLOSED
}

sealed class Message(
        private val type: MessageType,
        val seqNumber: Int,
        val ackNumber: Int,
        private val dataLength: Int,
        val data: ByteArray?
) {
    init {
        if(dataLength < 0) {
            throw BadBytesException("dataLength", dataLength)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Message

        if (type != other.type) return false
        if (seqNumber != other.seqNumber) return false
        if (ackNumber != other.ackNumber) return false
        if (!Arrays.equals(data, other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + seqNumber
        result = 31 * result + ackNumber
        result = 31 * result + Arrays.hashCode(data)
        return result
    }

    fun toBytes() : ByteArray {
        val byteStream = ByteArrayOutputStream(SERVICE_BUFFER_SIZE + dataLength)
        DataOutputStream(byteStream).use { output ->
            output.writeInt(type.ordinal)
            output.writeInt(seqNumber)
            output.writeInt(ackNumber)
            output.writeInt(dataLength)
            if(data != null) {
                output.write(data, 0, dataLength)
            }
        }
        return byteStream.toByteArray()
    }
}

class BadBytesException(
        field: String,
        value: Int
) : Exception("Failed to parse message on field $field with value $value")

fun ByteArray.toMessage() : Message {
    DataInputStream(ByteArrayInputStream(this)).use { inputStream ->
        if(size < SERVICE_BUFFER_SIZE) {
            throw BadBytesException("size", size)
        }

        val position = inputStream.readInt()

        val types = MessageType.values()
        if(position >= types.size) {
            throw BadBytesException("type", position)
        }
        val type = types[position]

        val seqNumber = inputStream.readInt()
        val ackNumber = inputStream.readInt()
        val dataLength = inputStream.readInt()

        return when(type) {
            MessageType.SYN -> if(ackNumber == -1) {
                SynMessage(seqNumber)
            } else {
                SynAckMessage(seqNumber, ackNumber)
            }

            MessageType.ACK -> {
                AckMessage(seqNumber, ackNumber)
            }

            MessageType.DATA -> {
                if(dataLength > 0) {
                    val data = ByteArray(dataLength)
                    inputStream.read(data, 0, dataLength)
                    DataMessage(seqNumber, ackNumber, data, dataLength)
                } else throw BadBytesException("dataLength", dataLength)
            }

            MessageType.FIN -> {
                FinMessage(seqNumber, ackNumber)
            }
        }
    }
}

class SynMessage(initSeqNumber: Int) : Message(MessageType.SYN, initSeqNumber, -1, 0, null)

class SynAckMessage(initSeqNumber: Int, firstByteNumber: Int) : Message(
        MessageType.SYN,
        initSeqNumber,
        firstByteNumber,
        0,
        null
)

class AckMessage(seqNumber: Int, ackNumber: Int) : Message(
        MessageType.ACK,
        seqNumber,
        ackNumber,
        0,
        null
)

class DataMessage(
        seqNumber: Int,
        ackNumber: Int,
        data: ByteArray,
        dataLength: Int
) : Message(
        MessageType.DATA,
        seqNumber,
        ackNumber,
        dataLength,
        data
)

class FinMessage(
        lastSeqNumber: Int,
        lastAckNumber: Int
) : Message(
        MessageType.FIN,
        lastSeqNumber,
        lastAckNumber,
        0,
        null
)

enum class MessageType {
    SYN,
    DATA,
    ACK,
    FIN
}

fun Message.toPacket(address: SocketAddress): DatagramPacket {
    val bytes = this.toBytes()
    return DatagramPacket(bytes, bytes.size, address)
}
