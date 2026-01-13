package cn.bincker.stream.sound.entity

import android.util.Log
import cn.bincker.stream.sound.BuildConfig
import cn.bincker.stream.sound.ProtocolMagicEnum
import cn.bincker.stream.sound.utils.HexUtils
import cn.bincker.stream.sound.utils.getCrc16
import cn.bincker.stream.sound.utils.putCrc16
import cn.bincker.stream.sound.utils.putSign
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
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
        ): Message<*> {
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
}

@OptIn(ExperimentalStdlibApi::class)
fun SocketChannel.writeMessage(queueNum: AtomicInteger, message: Message<*>, key: Ed25519PrivateKeyParameters) {
    Log.d(TAG, "writeMessage: $message")
    ByteBuffer.allocate(Message.minLength + message.packLength).apply {
        put(message.magic.magic)
        putInt(message.version)
        putInt(queueNum.getAndIncrement())
        putInt(message.id)
        val bodyBytes = message.body.toByteArray()
        putInt(bodyBytes.size)
        put(bodyBytes)
        putSign(key)
        putCrc16()
    }.let {
        it.flip()
        Log.d(TAG, "writeMessage: ${it.array().toHexString()}")
        write(it)
    }
}

fun ByteBuffer.getMessage(): Message<ByteArrayMessageBody>? {
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
    val crc = getCrc16()
    return when(magic) {
        ProtocolMagicEnum.ECDH,
        ProtocolMagicEnum.ECDH_RESPONSE,
        ProtocolMagicEnum.PAIR,
        ProtocolMagicEnum.PAIR_RESPONSE,
        ProtocolMagicEnum.AUTHENTICATION,
        ProtocolMagicEnum.AUTHENTICATION_RESPONSE,
        ProtocolMagicEnum.PLAY,
        ProtocolMagicEnum.PLAY_RESPONSE,
        ProtocolMagicEnum.STOP,
        ProtocolMagicEnum.STOP_RESPONSE -> {
            Message(
                magic = magic,
                version = version,
                queueNum = queueNum,
                id = id,
                packLength = length,
                body = ByteArrayMessageBody(bodyBytes),
                sign,
                crc
            )
        }
    }
}
