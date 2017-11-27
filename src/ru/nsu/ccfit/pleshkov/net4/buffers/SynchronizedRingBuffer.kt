package ru.nsu.ccfit.pleshkov.net4.buffers

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
