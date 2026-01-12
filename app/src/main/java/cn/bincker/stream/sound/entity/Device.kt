package cn.bincker.stream.sound.entity

import android.util.Log
import cn.bincker.stream.sound.ProtocolMagicEnum.ECDH
import cn.bincker.stream.sound.ProtocolMagicEnum.ECDH_RESPONSE
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.utils.loadPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicInteger

class Device(
    val config: DeviceConfig,
    var channel: SocketChannel? = null,
    val msgWaitTimeout: Long = 5000,
) {
    companion object {
        const val TAG = "Device"
    }
    private var connected: Boolean = false

    val isConnected get() = connected

    val publicKey: Ed25519PublicKeyParameters? = config.publicKey.let {
        if (it.isBlank()) null else loadPublicKey(it)
    }

    private var _ecdhCompleted = false
    val ecdhCompleted get() = _ecdhCompleted != null

    var sessionKey: ByteArray = ByteArray(32) { 0 }

    val messageQueueNum = AtomicInteger(0)
    val messageIdNum = AtomicInteger(0)
    var messageFlow: SharedFlow<Message<*>?>? = null

    val socketAddress get(): SocketAddress {
        val parts = config.address.split(":")
        val host = parts[0]
        val port = if (parts.size > 1) parts[1].toInt() else 12345
        return InetSocketAddress(host, port)
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        channel?.let {
            if (connected){
                connected = false
            }
            if(it.isOpen) it.close()
            channel = null
        }
        Log.d(TAG, "connect: ${config.address}")
        channel = SocketChannel.open(socketAddress)
        connected = true
        launch {
            listening()
        }
    }

    suspend fun listening() = withChannel {
        val buffer = ByteBuffer.allocate(2048)
        val msf = MutableSharedFlow<Message<*>?>(0, 10)
        messageFlow = msf
        try {
            while (connected && read(buffer) != -1) {
                buffer.flip()
                buffer.getMessage()?.let { msf.emit(it) }
                buffer.compact()
            }
        }finally {
            messageFlow = null
            if(connected) disconnect()
        }
    }

    suspend fun disconnect(){
        withContext(Dispatchers.IO) {
            connected = false
            channel?.let {
                if (it.isOpen) it.close()
                channel = null
            }
            Log.d(TAG, "disconnect: device [${config.name}] disconnected")
        }
    }

    fun checkConnect(){
        if (!connected || channel == null || channel?.isOpen != true || channel?.isConnected != true) throw Exception("device [${config.name}] not connected")
    }

    suspend fun withChannel(f: suspend SocketChannel. ()->Unit) {
        checkConnect()
        withContext(Dispatchers.IO) {
            f(channel!!)
        }
    }

    suspend fun ecdh(keyPair: AsymmetricCipherKeyPair) {
        withChannel {
            val msgId = messageIdNum.getAndIncrement()
            writeMessage(Message.build(
                ECDH,
                messageQueueNum.getAndIncrement(),
                msgId,
                ByteArrayMessageBody((keyPair.public as X25519PublicKeyParameters).encoded)
            ))
            val response = withTimeout(msgWaitTimeout) {
                messageFlow?.filter { it?.magic == ECDH_RESPONSE && it.id == msgId }?.first()
            } ?: throw Exception("device [${config.name}] ecdh no response received")
            if(response.body is ByteArrayMessageBody) {
                val ecdhKey = X25519PublicKeyParameters(response.body.data, 0)
                (keyPair.private as X25519PrivateKeyParameters).generateSecret(ecdhKey, sessionKey, 0)
                _ecdhCompleted = true
                Log.d(TAG, "ecdh: device [${config.name}] ecdh success")
            }
        }
    }
}