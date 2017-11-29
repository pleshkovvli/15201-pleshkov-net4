package ru.nsu.ccfit.pleshkov.net4

import ru.nsu.ccfit.pleshkov.net4.handlers.UDPStreamClosedException
import ru.nsu.ccfit.pleshkov.net4.sockets.UDPStreamServerSocket
import ru.nsu.ccfit.pleshkov.net4.sockets.UDPStreamSocket
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import kotlin.concurrent.thread

fun main(args: Array<String>) {

    val fileStr = "/home/pleshkovvli/15201-pleshkov"

    thread {
        val socket = UDPStreamSocket(InetAddress.getLocalHost(), 3113)

        socket.getOutputStream().use { outputStream ->
            val file = File(fileStr)
            val size = file.length()
            FileInputStream(file).use { inputStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                var writtenBytes = 0
                while (writtenBytes < size) {
                    val rest = size - writtenBytes
                    val bytesToRead = if (rest < DEFAULT_BUFFER_SIZE) rest
                    else DEFAULT_BUFFER_SIZE.toLong()

                    val readBytes = inputStream.read(buffer, 0, bytesToRead.toInt())
                    outputStream.write(buffer, 0, readBytes)
                    writtenBytes += readBytes
                }
            }
        }

        println("Thread finished!!!!!!!!!!!!!!")
    }


    val udpStrServerSock = UDPStreamServerSocket(3113)
    udpStrServerSock.listen()

    val socket = udpStrServerSock.accept()

    println("Accepted")

    val size = File(fileStr).length()
    try {
        socket.getInputStream().use { inputStream ->
            val file = File("/home/pleshkovvli/net4out")

            FileOutputStream(file).use { outputStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                var writtenBytes = 0
                while (writtenBytes < size) {
                    val rest = size - writtenBytes
                    val bytesToRead = if (rest < DEFAULT_BUFFER_SIZE) {
                        rest
                    } else DEFAULT_BUFFER_SIZE.toLong()
                    val readBytes = inputStream.read(buffer, 0, bytesToRead.toInt())
                    outputStream.write(buffer, 0, readBytes)
                    writtenBytes += readBytes
                }

                try {
                    inputStream.read()
                } catch (e: UDPStreamClosedException) {
                    print("AA")
                }
            }

            println("YEAH")
        }

    } catch (e: Exception) {
        println("Exc happened ${e.message}")
    }

    udpStrServerSock.close()

    println("THATS ALL")
}