//package ru.nsu.ccfit.pleshkov.net4.sockets
//
//import ru.nsu.ccfit.pleshkov.net4.messages.UDPStreamState
//import java.net.DatagramSocket
//import java.net.InetSocketAddress
//import java.util.concurrent.ArrayBlockingQueue
//import java.util.concurrent.BlockingQueue
//
//class UDPStreamServerSocket {
//    val udpSocket = DatagramSocket(3113).apply { soTimeout = TIMEOUT_MS }
//
//    private var state: UDPStreamState = UDPStreamState.NOT_CONNECTED
//
//    private val handler = ServerRoutineHandler(this)
//    val acceptingQueue = ArrayBlockingQueue<UDPStreamSocket>(10)
//    val sendingQueue = ArrayBlockingQueue<UDPStreamSocket>(20)
//
//    val clientSockets = HashMap<InetSocketAddress, UDPStreamSocket>()
//
//    fun bind(address: InetSocketAddress) {
//        udpSocket.bind(address)
//    }
//
//    fun listen() {
//        state = UDPStreamState.LISTENING
//
//        handler.start()
//    }
//
//    fun accept() : UDPStreamSocket {
//        return acceptingQueue.take()
//    }
//}