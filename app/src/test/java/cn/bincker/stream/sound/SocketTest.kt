package cn.bincker.stream.sound

import org.junit.Test
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel

class SocketTest {
    @Test
    fun tcpConnectTest() {
        SocketChannel.open(InetSocketAddress("127.0.0.1", 8910)).use {
            println(it.isOpen)
        }
    }
}