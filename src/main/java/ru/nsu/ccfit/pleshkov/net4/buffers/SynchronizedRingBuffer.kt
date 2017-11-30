package ru.nsu.ccfit.pleshkov.net4.buffers

open class SynchronizedRingBuffer(protected val maxSize: Int) {
    val available: Int
        get() = synchronized(lock) { availableBytes }

    protected open var begin = 0
    protected open var availableBytes = 0

    protected open val freeSpace
        get() = maxSize - availableBytes

    protected open val waitToWrite = false
    protected open val notifyOnWrite = false

    protected open val waitToRead = false
    protected open val notifyOnRead = false

    protected val lock = Object()

    private val buffer = ByteArray(maxSize)
    private var closing = false

    init {
        if (maxSize < 1) {
            throw IllegalArgumentException("Buffer size should be greater than zero")
        }
    }

    fun write(src: ByteArray, offset: Int, length: Int): Int = synchronized(lock) {
        validate(offset, length)

        while (waitToWrite && !closing) {
            lock.wait()
        }

        if (closing) {
            return 0
        }

        val toWrite = minOf(length, freeSpace)

        var index = 0
        val end = begin + availableBytes
        while (index < toWrite) {
            buffer[(index + end) % maxSize] = src[offset + index]
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

        while (waitToRead && !closing) {
            lock.wait()
        }

        if (closing) {
            return 0
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

    fun close() = synchronized(lock) {
        closing = true
        lock.notifyAll()
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
