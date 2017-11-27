package ru.nsu.ccfit.pleshkov.net4.sockets

open class SynchronizedRingBuffer(protected val maxSize: Int) {
    init {
        if (maxSize < 1) {
            throw IllegalArgumentException("Buffer size should be greater than zero")
        }
    }

    private val buffer = ByteArray(maxSize)
    open protected var begin = 0
    var availableBytes = 0
        protected set

    val dataAvailable
        get() = availableBytes > 0

    open protected val freeSpace
        get() = maxSize - availableBytes

    open protected val waitToWrite = false
    open protected val notifyOnWrite = false

    open protected val waitToRead = false
    open protected val notifyOnRead = false

    protected val lock = Object()

    fun write(src: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        validate(offset, length)

        while (waitToWrite) {
            lock.wait()
        }

        val toWrite = minOf(length, freeSpace)

        var index = 0
        while (index < toWrite) {
            buffer[(index + begin + availableBytes) % maxSize] = src[offset + index]
            ++index
        }

        availableBytes += toWrite

        if (notifyOnWrite) {
            lock.notifyAll()
        }

        return toWrite
    }

    fun read(dest: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        validate(offset, length)

        while (waitToRead) {
            lock.wait()
        }

        val toRead = minOf(length, availableBytes)

        var index = 0
        while (index < toRead) {
            dest[index + offset] = buffer[(index + begin) % maxSize]
            ++index
        }

        begin = (toRead + begin) % maxSize
        availableBytes -= toRead

        if (notifyOnRead) {
            lock.notifyAll()
        }

        return toRead
    }


    private fun validate(offset: Int, length: Int) {
        if (offset < 0) {
            throw IllegalArgumentException("Offset is less than zero")
        }

        if (length < 1) {
            throw IllegalArgumentException("Length should be greater than zero")
        }
    }
}

class RecvRingBuffer(maxSize: Int) : SynchronizedRingBuffer(maxSize) {
    override val notifyOnWrite = true
    override val waitToRead: Boolean
        get() = (availableBytes == 0)
}

class SendRingBuffer(maxSize: Int) : SynchronizedRingBuffer(maxSize) {
    private var bufOffset = 0

    override var begin = 0
        set(value) {
            bufOffset += if (value - field > 0) {
                value - field
            } else {
                (value + maxSize - field) % maxSize
            }

            field = value
        }

    override val freeSpace: Int
        get() = super.freeSpace - bufOffset

    override val waitToWrite: Boolean
        get() = (freeSpace == 0)
    override val notifyOnWrite = true

    override val waitToRead: Boolean
        get() = (availableBytes == 0)

    val allBytesSent: Boolean
        get() = (availableBytes == 0) && (bufOffset == 0)


    fun dropBufferOffset() = synchronized(lock) {
        val offsetWas = bufOffset
        begin = (begin + maxSize - bufOffset) % maxSize
        availableBytes += bufOffset
        bufOffset = 0
        lock.notifyAll()
        offsetWas
    }

    fun confirmRead(len: Int) = synchronized(lock) {
        bufOffset -= len
        if (bufOffset < 0) {
            bufOffset = 0
        }

        lock.notifyAll()
    }
}
