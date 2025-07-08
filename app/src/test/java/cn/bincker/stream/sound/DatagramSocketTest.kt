package cn.bincker.stream.sound

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

const val PORT = 8888

class DatagramSocketTest {
    @Test
    fun datagramSocketSend() {
        runBlocking {
            val data = ByteArray(1)
            for (i in 0 until 100) {
                DatagramSocket(8889).use { socket ->
                    val packet = DatagramPacket(data, data.size, InetSocketAddress("255.255.255.255",
                        PORT
                    ))
                    socket.send(packet)
                    delay(1000)
                }
            }
        }
    }

    @Test
    fun datagramChannenReceive() {
        DatagramChannel.open().use { channel->
            channel.bind(InetSocketAddress(PORT))
            val buffer = ByteBuffer.allocate(1200)
            var address: SocketAddress? = null
            while (channel.receive(buffer)?.also { address = it } == null);
            Assert.assertTrue(address is InetSocketAddress)
            Assert.assertEquals((address as InetSocketAddress).port, PORT)
        }
    }

    @Test
    fun datagramSocketReceive() {
        DatagramSocket(PORT).use { socket->
            val data = ByteArray(1200)
            val datagramPacket = DatagramPacket(data, data.size)
            socket.receive(datagramPacket)
            Assert.assertEquals(datagramPacket.port, PORT)
        }
    }

    @Test
    fun addressEquals() {
        val a = InetAddress.getByName("192.168.1.1")
        val b = InetAddress.getByName("192.168.1.1")
        Assert.assertEquals(a, b)
    }
}