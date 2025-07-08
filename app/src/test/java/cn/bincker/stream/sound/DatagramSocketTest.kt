package cn.bincker.stream.sound

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

class DatagramSocketTest {
    @Test
    fun send() {
        runBlocking {
            val data = ByteArray(5) {0x7f}
            val port = 8888
            while (true) {
                DatagramSocket().use { socket ->
                    val packet = DatagramPacket(data, data.size, InetSocketAddress("192.168.1.15", port))
                    socket.send(packet)
                    delay(1000)
                }
            }
        }
    }
}