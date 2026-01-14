package cn.bincker.stream.sound.entity

import android.util.Log
import cn.bincker.stream.sound.BuildConfig
import cn.bincker.stream.sound.ProtocolMagicEnum
import cn.bincker.stream.sound.utils.aes256gcmDecrypt
import cn.bincker.stream.sound.utils.aes256gcmEncrypt
import cn.bincker.stream.sound.utils.crc16
import cn.bincker.stream.sound.utils.getCrc16
import cn.bincker.stream.sound.utils.loadPublicEd25519
import cn.bincker.stream.sound.utils.loadPublicX25519
import cn.bincker.stream.sound.utils.putCrc16
import cn.bincker.stream.sound.utils.putSign
import cn.bincker.stream.sound.utils.sha256
import cn.bincker.stream.sound.utils.verifyCrc16
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

const val TAG = "Message"

interface MessageBody {
    fun toByteArray(): ByteArray
    fun length(): Int
}

data class ByteArrayMessageBody(
    val data: ByteArray
): MessageBody{
    override fun toByteArray(): ByteArray = data
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ByteArrayMessageBody

        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        return data.contentHashCode()
    }

    override fun length() = data.size

    fun decryptAes256gcm(key: ByteArray): Message<out MessageBody>? = ByteBuffer.wrap(fromAes256gcmEncryptedData(data, key).data).getMessage()

    companion object {
        const val IV_LENGTH = 16
        //从Aes加密数据解密
        fun fromAes256gcmEncryptedData(encryptedData: ByteArray, key: ByteArray): ByteArrayMessageBody {
            if (encryptedData.size < IV_LENGTH) throw IllegalArgumentException("encryptedData length less than $IV_LENGTH")
            val iv = ByteArray(IV_LENGTH)
            System.arraycopy(encryptedData, 0, iv, 0, IV_LENGTH)
            val cipherText = ByteArray(encryptedData.size - IV_LENGTH)
            System.arraycopy(encryptedData, IV_LENGTH, cipherText, 0, cipherText.size)
            return ByteArrayMessageBody(aes256gcmDecrypt(cipherText, key, iv))
        }
        //构建Aes加密数据包体
        fun buildAes256gcmEncryptedBody(plainData: ByteArray, key: ByteArray): ByteArrayMessageBody {
            val iv = ByteArray(IV_LENGTH).also {
                SecureRandom().nextBytes(it)
            }
            val cipherText = aes256gcmEncrypt(plainData, key, iv)
            val encryptedData = ByteArray(IV_LENGTH + cipherText.size)
            System.arraycopy(iv, 0, encryptedData, 0, IV_LENGTH)
            System.arraycopy(cipherText, 0, encryptedData, IV_LENGTH, cipherText.size)
            return ByteArrayMessageBody(encryptedData)
        }
    }
}

data class Ed25519PublicKeyMessageBody(
    val publicKey: Ed25519PublicKeyParameters
): MessageBody{
    override fun toByteArray(): ByteArray = publicKey.encoded
    override fun length() = publicKey.encoded.size
}

data class X25519PublicKeyMessageBody(
    val publicKey: X25519PublicKeyParameters
): MessageBody{
    override fun toByteArray(): ByteArray = publicKey.encoded
    override fun length() = publicKey.encoded.size
}

data class StringMessageBody(
    val message: String
): MessageBody{
    override fun toByteArray(): ByteArray = message.toByteArray()
    override fun length() = message.toByteArray().size
}

data class Message<T: MessageBody>(
    val magic: ProtocolMagicEnum,
    val version: Int,
    val queueNum: Int,
    val id: Int,
    val packLength: Int,
    val body: T,
    val sign: ByteArray,
    val crc: Int
){
    companion object{
        val minLength = ProtocolMagicEnum.minMagicLen + Int.SIZE_BYTES * 4 + Short.SIZE_BYTES + Ed25519.SIGNATURE_SIZE

        fun build(
            magic: ProtocolMagicEnum,
            id: Int,
            body: MessageBody
        ): Message<out MessageBody> {
            return Message(
                magic = magic,
                version = BuildConfig.VERSION_CODE,
                queueNum = 0,
                id = id,
                packLength = body.length(),
                body = body,
                sign = ByteArray(0),
                crc = 0
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Message<*>

        if (version != other.version) return false
        if (queueNum != other.queueNum) return false
        if (id != other.id) return false
        if (packLength != other.packLength) return false
        if (crc != other.crc) return false
        if (magic != other.magic) return false
        if (body != other.body) return false
        if (!sign.contentEquals(other.sign)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + queueNum
        result = 31 * result + id
        result = 31 * result + packLength
        result = 31 * result + crc
        result = 31 * result + magic.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + sign.contentHashCode()
        return result
    }

    fun <B: MessageBody> transferBody(body: B) = Message<B>(
        magic = this.magic,
        version = this.version,
        queueNum = this.queueNum,
        id = this.id,
        packLength = this.packLength,
        body = body,
        sign = this.sign,
        crc = this.crc
    )

    fun toByteBuffer(queueNum: Int, key: Ed25519PrivateKeyParameters): ByteBuffer = ByteBuffer.allocate(
        minLength + packLength).apply {
        put(magic.magic)
        putInt(version)
        putInt(queueNum)
        putInt(id)
        val bodyBytes = body.toByteArray()
        putInt(bodyBytes.size)
        put(bodyBytes)
        putSign(key)
        putCrc16()
    }

    fun toAes256gcmEncryptedMessage(queueNum: Int, signKey: Ed25519PrivateKeyParameters, encryptKey: ByteArray) = Message(
        magic = ProtocolMagicEnum.ENCRYPTED,
        version = this.version,
        queueNum = queueNum,
        id = this.id,
        packLength = 0,
        body = ByteArrayMessageBody.buildAes256gcmEncryptedBody(toByteBuffer(queueNum, signKey).array(), encryptKey),
        sign = ByteArray(0),
        crc = 0
    )
}

@OptIn(ExperimentalStdlibApi::class)
fun SocketChannel.writeMessage(queueNum: Int, message: Message<*>, key: Ed25519PrivateKeyParameters) {
    Log.d(TAG, "writeMessage: $message")
    message.toByteBuffer(queueNum, key).let {
        it.flip()
        Log.d(TAG, "writeMessage: ${it.array().toHexString()}")
        write(it)
    }
}

@OptIn(ExperimentalStdlibApi::class)
fun ByteBuffer.getMessage(): Message<out MessageBody>? {
    if (remaining() < Message.minLength) return null
    mark()
    val magic = ProtocolMagicEnum.match(this) ?: run {
        reset()
        return null
    }
    val version = getInt()
    val queueNum = getInt()
    val id = getInt()
    val length = getInt()
    if (remaining() < length + Short.SIZE_BYTES) {
        reset()
        return null
    }
    val bodyBytes = ByteArray(length)
    get(bodyBytes)
    val sign = ByteArray(Ed25519.SIGNATURE_SIZE)
    get(sign)
    val validCrc = verifyCrc16()
    val crc = getCrc16()
    if (!validCrc){
        Log.d(TAG, "getMessage: invalid crc, crc=$crc\tcalcCrc=${position(position() - 2).let { crc16() }}\thex=${array().toHexString()}")
        return null
    }
    return Message(
        magic = magic,
        version = version,
        queueNum = queueNum,
        id = id,
        packLength = length,
        body = ByteArrayMessageBody(bodyBytes),
        sign,
        crc
    ).let { resolveMessage(it) }
}

fun resolveMessage(msg: Message<ByteArrayMessageBody>): Message<out MessageBody> = when(msg.magic) {
    ProtocolMagicEnum.PAIR,
    ProtocolMagicEnum.PAIR_RESPONSE, ->
        msg.transferBody(
            Ed25519PublicKeyMessageBody(loadPublicEd25519(msg.body.data))
        )
    ProtocolMagicEnum.ECDH,
    ProtocolMagicEnum.ECDH_RESPONSE, ->
        msg.transferBody(
            X25519PublicKeyMessageBody(loadPublicX25519(msg.body.data))
        )
    ProtocolMagicEnum.AUTHENTICATION,
    ProtocolMagicEnum.AUTHENTICATION_RESPONSE,
    ProtocolMagicEnum.PLAY,
    ProtocolMagicEnum.PLAY_RESPONSE,
    ProtocolMagicEnum.STOP,
    ProtocolMagicEnum.STOP_RESPONSE,
    ProtocolMagicEnum.ENCRYPTED-> {
        msg
    }
    ProtocolMagicEnum.ERROR -> {
        msg.transferBody(StringMessageBody(String(msg.body.data)))
    }
}
