package ru.nsu.ccfit.pleshkov.net4

import ru.nsu.ccfit.pleshkov.net4.sockets.ClientRoutineHandler
import ru.nsu.ccfit.pleshkov.net4.sockets.UDPStreamServerSocket
import ru.nsu.ccfit.pleshkov.net4.sockets.UDPStreamSocket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

fun main(args: Array<String>) {
    val serverSocket = UDPStreamServerSocket()
    //serverSocket.bind(InetSocketAddress(3113))
    serverSocket.listen()

    thread {
        Thread.sleep(1000)
        val udpSocket = DatagramSocket()
        val sock = UDPStreamSocket(udpSocket)
        val buf = "Hello".toByteArray()

        sock.connect(InetSocketAddress(InetAddress.getLocalHost(), 3113))
        sock.send(buf, 0, buf.size)
        Thread.sleep(100000)
    }

    val socket = serverSocket.accept()

    println("accepted")

    val buf = ByteArray(100)

    socket.recv(buf, 0, 5)

    println(String(buf.copyOfRange(0, 5)))

}