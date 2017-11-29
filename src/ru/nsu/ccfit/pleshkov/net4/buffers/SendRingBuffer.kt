package ru.nsu.ccfit.pleshkov.net4.buffers

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

    override val notifyOnRead = true

    val allBytesSent: Boolean
        get() = (availableBytes == 0) && (bufOffset == 0)


    fun dropBufferOffset() = synchronized(lock) {
        val offsetWas = bufOffset

        begin = (begin + maxSize - bufOffset) % maxSize
        availableBytes += offsetWas

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
