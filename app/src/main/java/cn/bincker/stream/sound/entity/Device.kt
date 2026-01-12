package cn.bincker.stream.sound.entity

import android.util.Log
import cn.bincker.stream.sound.ProtocolMagicEnum.ECDH
import cn.bincker.stream.sound.ProtocolMagicEnum.ECDH_RESPONSE
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.utils.hmacDeriveKey
import cn.bincker.stream.sound.utils.loadPublicKey
import kotlinx.coroutines.CoroutineScope
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

    suspend fun connect() {
        withContext(Dispatchers.IO) {
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
        }
    }

    fun startListening(scope: CoroutineScope): Job {
        return scope.launch(Dispatchers.IO) {
            listening()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    suspend fun listening() = withChannel {
        val buffer = ByteBuffer.allocate(2048)
        val msf = MutableSharedFlow<Message<*>?>(0, 10)
        messageFlow = msf
        try {
            while (connected && read(buffer) != -1) {
                buffer.flip()
                val msg = buffer.getMessage()
                if (msg != null){
                    msf.emit(msg)
                }else{
                    Log.d(TAG, "listening: invalid msg data=${buffer.array().copyOfRange(buffer.position(), buffer.position() + buffer.limit()).toHexString()}")
                }
                buffer.compact()
            }
        }catch (e: Exception){
            Log.e(TAG, "listening: receive message error", e)
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

    @OptIn(ExperimentalStdlibApi::class)
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

                // 生成原始 X25519 共享密钥
                val rawSharedSecret = ByteArray(32)
                (keyPair.private as X25519PrivateKeyParameters).generateSecret(ecdhKey, rawSharedSecret, 0)

                // 使用 HMAC-SHA256 派生最终会话密钥 (与 C++ 端保持一致)
                val derivedKey = hmacDeriveKey(rawSharedSecret, salt = ByteArray(0))
                derivedKey.copyInto(sessionKey, 0, 0, 32)

                _ecdhCompleted = true
                Log.d(TAG, "ecdh: device [${config.name}] ecdh success, sessionKey=${sessionKey.toHexString()}")
            }
        }
    }
}