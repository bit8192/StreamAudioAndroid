package cn.bincker.stream.sound.entity

import android.util.Log
import cn.bincker.stream.sound.BuildConfig
import cn.bincker.stream.sound.ProtocolMagicEnum
import cn.bincker.stream.sound.ProtocolMagicEnum.*
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.utils.loadPublicKey
import cn.bincker.stream.sound.utils.putCrc16
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicInteger

class Device(
    val config: DeviceConfig,
    var connected: Boolean = false,
    var channel: SocketChannel? = null,
) {
    companion object {
        const val TAG = "Device"
    }

    val publicKey: Ed25519PublicKeyParameters? = config.publicKey.let {
        if (it.isBlank()) null else loadPublicKey(it)
    }

    var ecdhKey: X25519PublicKeyParameters? = null
    val messageQueueNum = AtomicInteger(0)
    var messages: MutableStateFlow<Message<Any>?>? = null

    val socketAddress get(): SocketAddress {
        val parts = config.address.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toInt() else 12345
        return InetSocketAddress(host, port)
    }

    suspend fun connect(key: X25519PublicKeyParameters) {
        withContext(Dispatchers.IO) {
            channel?.let {
                if (connected){
                    connected = false
                }
                if(it.isOpen) it.close()
                channel = null
            }
            channel = SocketChannel.open(socketAddress)
            launch {
                listening()
            }
            ecdh(key)
        }
    }

    suspend fun listening() = withChannel {
        val buffer = ByteBuffer.allocate(2048)
        messages = MutableStateFlow(null)
        while (connected && read(buffer) != -1){
            buffer.flip()
            buffer.mark()
            val magic = ProtocolMagicEnum.match(buffer)
            if (magic == null){
                if (buffer.remaining() > ProtocolMagicEnum.minMagicLen) {
                    buffer.position(buffer.position() + buffer.remaining() - ProtocolMagicEnum.minMagicLen)
                    buffer.compact()
                }
                continue
            }
            val version = buffer.getInt()
            val num = buffer.getInt()
            val length = buffer.getInt()
            if (length < 1 || length > buffer.capacity()) {
                Log.e(TAG, "listening: invalid pack length, length=${length}")
                buffer.compact()
                continue
            }
            val data = buffer.get
            when(magic) {
                ECDH -> {
                }
                ECDH_RESPONSE -> TODO()
                PAIR -> TODO()
                PAIR_RESPONSE -> TODO()
                AUTHENTICATION -> TODO()
                AUTHENTICATION_RESPONSE -> TODO()
                PLAY -> TODO()
                PLAY_RESPONSE -> TODO()
                STOP -> TODO()
                STOP_RESPONSE -> TODO()
            }
        }
    }

    fun checkConnect(){
        if (!connected || channel == null || channel?.isOpen != true || channel?.isConnected != true) throw Exception("device [${config.name}] not connected")
    }

    fun withChannel(f: SocketChannel.()->Unit) {
        checkConnect()
        f(channel!!)
    }

    fun ecdh(key: X25519PublicKeyParameters) {
        withChannel {
            write(buildEcdhRequest(key))
            readEcdhResponse()
        }
    }

    fun buildEcdhRequest(key: X25519PublicKeyParameters) = buildPackage(ProtocolMagicEnum.ECDH, key.encoded)

    private fun buildPackage(magic: ProtocolMagicEnum, body: ByteArray): ByteBuffer {
        //魔数 + 版本号 + 序列号 + 包长度 + 包 + CRC校验码
        val buffer = ByteBuffer.allocate(magic.magic.size + Int.SIZE_BYTES + Int.SIZE_BYTES + body.size + Short.SIZE_BYTES)
        buffer.put(magic.magic)
        buffer.putInt(BuildConfig.VERSION_CODE)
        buffer.putInt(messageQueueNum.getAndIncrement())
        buffer.putInt(buffer.capacity())
        buffer.putCrc16()
        return buffer
    }
}