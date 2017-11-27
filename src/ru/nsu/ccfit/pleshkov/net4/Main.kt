package ru.nsu.ccfit.pleshkov.net4

import java.net.InetAddress
import kotlin.concurrent.thread

fun main(args: Array<String>) {

    thread {
        val socket = UDPStrSock(InetAddress.getLocalHost(), 3113)
        while (true) {
            val str = readLine() ?: continue

            if(str == "END") {
                break
            }

            val buf = str.toByteArray()
            if(buf.size < 1) {
                continue
            }
            val sended = socket.send(buf, 0, buf.size)
            println("SENDED $sended")
        }

        socket.close()
    }


    val udpStrServerSock = UDPStrServerSock(3113)
    udpStrServerSock.listen()

    val sock = udpStrServerSock.accept()

    println("ACCEPTED")
    try {
        val buf = ByteArray(10000)
        while (true) {
            val recved = sock.recv(buf, 0, 10000)
            println("RECVD: $recved")
            println(String(buf.copyOfRange(0, recved)))
        }
    } catch (e: Exception) {
        println("EXC: ${e.message}")
    }
}