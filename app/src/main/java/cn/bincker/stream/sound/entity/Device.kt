package cn.bincker.stream.sound.entity

import android.util.Log
import cn.bincker.stream.sound.ProtocolMagicEnum
import cn.bincker.stream.sound.ProtocolMagicEnum.ECDH
import cn.bincker.stream.sound.ProtocolMagicEnum.ECDH_RESPONSE
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.service.DeviceConnectionManager
import cn.bincker.stream.sound.utils.hMacSha256
import cn.bincker.stream.sound.utils.loadPublicEd25519
import cn.bincker.stream.sound.utils.loadPublicX25519
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
import cn.bincker.stream.sound.utils.toHexString

class Device(
    val connectionManager: DeviceConnectionManager?,
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
    val ecdhCompleted get() = _ecdhCompleted

    var sessionKey: ByteArray = ByteArray(32) { 0 }

    val messageQueueNum = AtomicInteger(0)
    val messageIdNum = AtomicInteger(0)
    var messageFlow: SharedFlow<Message<out MessageBody>?>? = null

    // UDP Audio Receiver
    private var udpAudioReceiver: UdpAudioReceiver? = null

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

    suspend fun listening() = withChannel {
        val buffer = ByteBuffer.allocate(2048)
        val msf = MutableSharedFlow<Message<out MessageBody>?>(0, 10)
        messageFlow = msf
        try {
            while (connected && read(buffer) != -1) {
                buffer.flip()
                val msg = buffer.getMessage(publicKey)
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

            // Stop UDP receiver if running
            try {
                udpAudioReceiver?.stop()
                udpAudioReceiver = null
            } catch (e: Exception) {
                Log.e(TAG, "disconnect: Failed to stop UDP receiver", e)
            }

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
                    msg.body.decryptAes256gcmToMsg(sessionKey, publicKey)
                } else {
                    throw Exception("device [${config.name}] invalid encrypted message body")
                }
            }
            else -> msg
        }
    }

    private suspend inline fun <reified B: MessageBody> waitResponse(id: Int, magic: ProtocolMagicEnum, timeout: Long = msgWaitTimeout): Message<B> = withTimeout(timeout) {
        messageFlow?.filter { it?.id == id }?.first()?.let { resolveMessage(it) }?.let {
            if (it.magic != magic) throw Exception("device [${config.name}] unexpected response magic: ${it.magic} != $magic")
            if (it.body !is B) throw Exception("device [${config.name}] unexpected response body type: ${it.body::class.java} != ${B::class.java}")
            @Suppress("UNCHECKED_CAST")
            it as Message<B>
        }
    } ?: throw Exception("device [${config.name}] ecdh no response received")

    suspend fun pair(pairCode: String) {
        val manager = connectionManager
            ?: throw IllegalStateException("AppConfigRepository is required for pairing")

        withChannel {
            val msgId = messageIdNum.getAndIncrement()
            val queueNum = messageQueueNum.getAndIncrement()
            val key = Base64.getDecoder().decode(pairCode).sha256()
            Log.d(TAG, "pair: key=${key.toHexString()}}")
            writeMessage(
                queueNum,
                Message.build(
                    ProtocolMagicEnum.PAIR,
                    msgId,
                    ByteArrayMessageBody.buildAes256gcmEncryptedBody(manager.publicKey.encoded, key)
                ),
                manager.privateKey
            )
            val response = waitResponse<ByteArrayMessageBody>(msgId, ProtocolMagicEnum.PAIR_RESPONSE)
            loadPublicEd25519(response.body.decryptAes256gcm(manager.publicKey.encoded.sha256()).data).let {
                publicKey = it
                config.publicKey = Base64.getEncoder().encodeToString(it.encoded)
                Log.d(TAG, "pair success: mineKey=${manager.publicKey.encoded.toHexString()}\tthitherKey=${it.encoded.toHexString()}")
            }
        }
    }

    /**
     * 已配对设备的快速认证
     * 使用存储的公钥进行身份验证，避免重复配对
     */
    suspend fun authenticate() {
        val manager = connectionManager
            ?: throw IllegalStateException("DeviceConnectionManager is required for authentication")

        withChannel {
            val msgId = messageIdNum.getAndIncrement()
            val queueNum = messageQueueNum.getAndIncrement()

            // 生成设备标识（公钥的sha256）
            val deviceIdentifier = manager.publicKey.encoded.sha256()

            // 生成32字节随机挑战值
            val randomChallenge = ByteArray(32).apply {
                java.security.SecureRandom().nextBytes(this)
            }

            Log.d(TAG, "authenticate: deviceId=${deviceIdentifier.toHexString()}")

            // 构建并发送认证消息
            writeMessage(
                queueNum,
                Message.build(
                    ProtocolMagicEnum.AUTHENTICATION,
                    msgId,
                    AuthenticationMessageBody(deviceIdentifier, randomChallenge)
                ),
                manager.privateKey  // 使用私钥签名
            )

            // 等待认证响应
            val response = waitResponse<AuthenticationResponseMessageBody>(
                msgId,
                ProtocolMagicEnum.AUTHENTICATION_RESPONSE
            )

            // 检查认证结果
            if (!response.body.success) {
                throw Exception("Authentication failed: ${response.body.errorMessage}")
            }

            // 从配置中加载服务器的公钥
            publicKey = config.publicKey.let {
                if (it.isBlank()) {
                    throw Exception("No server public key stored for authenticated device")
                } else {
                    loadPublicEd25519(it)
                }
            }

            Log.i(TAG, "authenticate: device [${config.name}] authenticated successfully")
        }
    }

    suspend fun ecdh() {
        val repository = connectionManager
            ?: throw IllegalStateException("AppConfigRepository is required for ECDH")

        withChannel {
            val msgId = messageIdNum.getAndIncrement()
            val queueNum = messageQueueNum.getAndIncrement()

            // Use SERVER's Ed25519 public key SHA256 for encryption
            val encryptKey = publicKey?.encoded?.sha256()
                ?: throw Exception("No server public key for ECDH")

            writeMessage(
                queueNum,
                Message.build(
                    ECDH,
                    msgId,
                    ByteArrayMessageBody.buildAes256gcmEncryptedBody((repository.ecdhKeyPair.public as X25519PublicKeyParameters).encoded, encryptKey)
                ),
                repository.privateKey
            )

            waitResponse<ByteArrayMessageBody>(msgId, ECDH_RESPONSE).let { response->
                // Decrypt with OWN Ed25519 public key SHA256
                val decryptKey = repository.publicKey.encoded.sha256()
                val decryptedMsg = response.body.decryptAes256gcm(decryptKey)
                val serverX25519PublicKey = loadPublicX25519(decryptedMsg.data)

                // 生成原始 X25519 共享密钥
                val rawSharedSecret = ByteArray(X25519.POINT_SIZE)
                (repository.ecdhKeyPair.private as X25519PrivateKeyParameters)
                    .generateSecret(serverX25519PublicKey, rawSharedSecret, 0)
                sessionKey = rawSharedSecret.sha256()

                _ecdhCompleted = true
                Log.d(TAG, "ecdh: device [${config.name}] ecdh success, sessionKey=${sessionKey.toHexString()}")
            }
        }
    }

    private fun deriveUdpAudioKey(tcpSessionKey: ByteArray): ByteArray {
        val salt = "udp-audio".toByteArray()
        val info = "stream-audio-v1".toByteArray()
        return hMacSha256(tcpSessionKey, salt + info)
    }

    suspend fun play(udpPort: Int = 8888) {
        val repository = connectionManager
            ?: throw IllegalStateException("AppConfigRepository is required for play")

        withChannel {
            if (!ecdhCompleted) {
                throw Exception("ECDH not completed, cannot play")
            }

            val msgId = messageIdNum.getAndIncrement()
            val queueNum = messageQueueNum.getAndIncrement()

            // Derive UDP audio key
            val udpAudioKey = deriveUdpAudioKey(sessionKey)

            // Build PLAY request with client UDP port
            val bodyBytes = ByteBuffer.allocate(2).apply {
                putShort(udpPort.toShort())
            }.array()

            writeMessage(
                queueNum,
                Message.build(
                    ProtocolMagicEnum.PLAY,
                    msgId,
                    ByteArrayMessageBody(bodyBytes)
                ).toAes256gcmEncryptedMessage(
                    queueNum,
                    repository.privateKey,
                    sessionKey
                ),
                repository.privateKey
            )

            // Wait for PLAY_RESPONSE
            waitResponse<ByteArrayMessageBody>(msgId, ProtocolMagicEnum.PLAY_RESPONSE).let { msg ->
                val body = msg.body.data
                val buffer = ByteBuffer.wrap(body)

                val serverUdpPort = buffer.getShort().toInt() and 0xFFFF
                val sampleRate = buffer.getInt()
                val bits = buffer.getShort().toInt() and 0xFFFF
                val channels = buffer.getShort().toInt() and 0xFFFF
                val format = buffer.getShort().toInt() and 0xFFFF

                Log.i(TAG, "play: PLAY_RESPONSE received - port=$serverUdpPort, sr=$sampleRate, bits=$bits, ch=$channels, fmt=$format")
                Log.d(TAG, "play: device [${config.name}] play started, udpKey=${udpAudioKey.toHexString()}")

                // Start UDP receiver with udpAudioKey
                try {
                    val serverAddress = channel!!.remoteAddress as InetSocketAddress
                    udpAudioReceiver = UdpAudioReceiver(
                        serverAddress = serverAddress.address,
                        clientPort = udpPort,
                        udpAudioKey = udpAudioKey,
                        sampleRate = sampleRate,
                        bits = bits,
                        channels = channels,
                        format = format
                    )

                    // Use the context from the current coroutine scope
                    val receiverScope = CoroutineScope(Dispatchers.IO)
                    udpAudioReceiver?.start(receiverScope)

                    Log.i(TAG, "play: UDP audio receiver started on port $udpPort")

                } catch (e: Exception) {
                    Log.e(TAG, "play: Failed to start UDP receiver", e)
                    udpAudioReceiver = null
                    throw e
                }
            }
        }
    }

    suspend fun stop() {
        val repository = connectionManager
            ?: throw IllegalStateException("AppConfigRepository is required for stop")

        withChannel {
            val msgId = messageIdNum.getAndIncrement()
            val queueNum = messageQueueNum.getAndIncrement()

            // Send STOP request (empty body)
            writeMessage(
                queueNum,
                Message.build(
                    ProtocolMagicEnum.STOP,
                    msgId,
                    ByteArrayMessageBody(ByteArray(0))
                ).toAes256gcmEncryptedMessage(
                    queueNum,
                    repository.privateKey,
                    sessionKey
                ),
                repository.privateKey
            )

            // Wait for STOP_RESPONSE
            waitResponse<ByteArrayMessageBody>(msgId, ProtocolMagicEnum.STOP_RESPONSE).let { response ->
                val body = response.body.data
                val status = if (body.isNotEmpty()) body[0].toInt() else 0

                Log.i(TAG, "stop: STOP_RESPONSE received - status=$status")
                Log.d(TAG, "stop: device [${config.name}] stopped")

                // Stop UDP receiver
                try {
                    udpAudioReceiver?.stop()
                    udpAudioReceiver = null
                    Log.i(TAG, "stop: UDP audio receiver stopped")
                } catch (e: Exception) {
                    Log.e(TAG, "stop: Failed to stop UDP receiver", e)
                }
            }
        }
    }
}