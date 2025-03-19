package com.github.xfalcon.vhosts.vservice

import java.net.DatagramSocket
import java.net.Socket

interface IProtector {
    fun protect(channel: DatagramSocket): Boolean
    fun protect(socket: Socket): Boolean
    fun protect(fd: Int): Boolean
}


