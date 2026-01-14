package cn.bincker.stream.sound.entity

import android.util.Log
import cn.bincker.stream.sound.ProtocolMagicEnum
import cn.bincker.stream.sound.ProtocolMagicEnum.ECDH
import cn.bincker.stream.sound.ProtocolMagicEnum.ECDH_RESPONSE
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.repository.AppConfigRepository
import cn.bincker.stream.sound.utils.hMacSha256
import cn.bincker.stream.sound.utils.loadPublicEd25519
import cn.bincker.stream.sound.utils.sha256
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
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.math.ec.rfc7748.X25519
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class Device(
    val appConfigRepository: AppConfigRepository,
    val config: DeviceConfig,
    var channel: SocketChannel? = null,
    val msgWaitTimeout: Long = 5000,
) {
    companion object {
        const val TAG = "Device"
    }
    private var connected: Boolean = false

    val isConnected get() = connected

    var publicKey: Ed25519PublicKeyParameters? = config.publicKey.let {
        if (it.isBlank()) null else loadPublicEd25519(it)
    }

    private var _ecdhCompleted = false
    val ecdhCompleted get() = _ecdhCompleted != null

    var sessionKey: ByteArray = ByteArray(32) { 0 }

    val messageQueueNum = AtomicInteger(0)
    val messageIdNum = AtomicInteger(0)
    var messageFlow: SharedFlow<Message<out MessageBody>?>? = null

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
        val msf = MutableSharedFlow<Message<out MessageBody>?>(0, 10)
        messageFlow = msf
        try {
            while (connected && read(buffer) != -1) {
                buffer.flip()
                val msg = buffer.getMessage()
                if(msg == null){
                    Log.d(TAG, "listening: incomplete msg data=${buffer.array().copyOfRange(buffer.position(), buffer.position() + buffer.limit()).toHexString()}")
                    buffer.compact()
                    continue
                }
                when(msg.magic){
                    ProtocolMagicEnum.PAIR -> {
                        //TODO 作为服务端
                    }
                    else -> msf.emit(msg)
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

    private fun resolveMessage(msg: Message<out MessageBody>): Message<out MessageBody>? {
        return when(msg.magic){
            ProtocolMagicEnum.ERROR -> {
                val errorMsg =
                    if (msg.body is StringMessageBody) msg.body.message else "unknown error"
                throw Exception("device [${config.name}] response error: $errorMsg")
            }
            ProtocolMagicEnum.ENCRYPTED -> {
                return if (msg.body is ByteArrayMessageBody) {
                    msg.body.decryptAes256gcm(sessionKey)
                } else {
                    throw Exception("device [${config.name}] invalid encrypted message body")
                }
            }
            else -> msg
        }
    }

    private suspend inline fun <reified B: MessageBody> waitResponse(id: Int, magic: ProtocolMagicEnum, timeout: Long = msgWaitTimeout): Message<B> = withTimeout(timeout) {
        messageFlow?.filter { it?.id == id }?.first()?.let { resolveMessage(it) }?.let {
            if (it.magic != magic || it.body !is B) throw Exception("device [${config.name}] unexpected response magic: ${it.magic} != $magic")
            @Suppress("UNCHECKED_CAST")
            it as Message<B>
        }
    } ?: throw Exception("device [${config.name}] ecdh no response received")

    suspend fun pair(pairCode: String) {
        withChannel {
            val msgId = messageIdNum.getAndIncrement()
            val queueNum = messageQueueNum.getAndIncrement()
            writeMessage(
                queueNum,
                Message.build(
                    ProtocolMagicEnum.PAIR,
                    msgId,
                    Ed25519PublicKeyMessageBody(appConfigRepository.publicKey)
                )
                    .toAes256gcmEncryptedMessage(
                        queueNum,
                        appConfigRepository.privateKey,
                        pairCode.toByteArray().sha256()
                    ),
                appConfigRepository.privateKey
            )
            val response = waitResponse<Ed25519PublicKeyMessageBody>(msgId, ProtocolMagicEnum.PAIR_RESPONSE)
            publicKey = response.body.publicKey
            config.publicKey = Base64.getEncoder().encodeToString(response.body.publicKey.encoded)
            appConfigRepository.addDeviceConfig(config)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    suspend fun ecdh() {
        withChannel {
            val msgId = messageIdNum.getAndIncrement()
            val queueNum = messageQueueNum.getAndIncrement()
            writeMessage(
                queueNum,
                Message.build(
                    ECDH,
                    msgId,
                    X25519PublicKeyMessageBody(appConfigRepository.ecdhKeyPair.public as X25519PublicKeyParameters)
                )
                    .toAes256gcmEncryptedMessage(
                        queueNum,
                        appConfigRepository.privateKey,
                        appConfigRepository.publicKey.encoded.sha256()
                    ),
                appConfigRepository.privateKey
            )
            waitResponse<X25519PublicKeyMessageBody>(msgId, ECDH_RESPONSE).let { response->
                // 生成原始 X25519 共享密钥
                val rawSharedSecret = ByteArray(X25519.POINT_SIZE)
                (appConfigRepository.ecdhKeyPair.private as X25519PrivateKeyParameters)
                    .generateSecret(response.body.publicKey, rawSharedSecret, 0)
                sessionKey = rawSharedSecret.sha256()

                _ecdhCompleted = true
                Log.d(TAG, "ecdh: device [${config.name}] ecdh success, sessionKey=${sessionKey.toHexString()}")
            }
        }
    }
}