package ru.nsu.ccfit.pleshkov.net4.buffers

class RecvRingBuffer(maxSize: Int) : SynchronizedRingBuffer(maxSize) {
    override val notifyOnWrite = true
    override val waitToRead: Boolean
        get() = (availableBytes == 0)
}
