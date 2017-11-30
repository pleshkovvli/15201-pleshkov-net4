package ru.nsu.ccfit.pleshkov.net4.handlers

open class UDPStreamSocketException(message: String) : Exception(message)

class UDPStreamNotConnectedException : UDPStreamSocketException("Socket is not connected")

class UDPStreamSocketTimeoutException(reason: String)
    : UDPStreamSocketException("Time to $reason exceeded")

class UDPStreamClosedException : UDPStreamSocketException("Socket closed")
