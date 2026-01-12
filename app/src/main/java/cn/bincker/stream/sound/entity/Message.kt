package cn.bincker.stream.sound.entity

import android.util.Log
import cn.bincker.stream.sound.BuildConfig
import cn.bincker.stream.sound.ProtocolMagicEnum
import cn.bincker.stream.sound.utils.HexUtils
import cn.bincker.stream.sound.utils.getCrc16
import cn.bincker.stream.sound.utils.putCrc16
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

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
    val crc: Int
){
    companion object{
        val minLength = ProtocolMagicEnum.minMagicLen + Int.SIZE_BYTES * 4 + Short.SIZE_BYTES

        fun build(
            magic: ProtocolMagicEnum,
            queueNum: Int,
            id: Int,
            body: MessageBody
        ): Message<*> {
            return Message(
                magic = magic,
                version = BuildConfig.VERSION_CODE,
                queueNum = queueNum,
                id = id,
                packLength = body.length(),
                body = body,
                crc = 0
            )
        }
    }
}

@OptIn(ExperimentalStdlibApi::class)
fun SocketChannel.writeMessage(message: Message<*>) {
    Log.d(TAG, "writeMessage: $message")
    ByteBuffer.allocate(Message.minLength + message.packLength).apply {
        put(message.magic.magic)
        putInt(message.version)
        putInt(message.queueNum)
        putInt(message.id)
        val bodyBytes = message.body.toByteArray()
        putInt(bodyBytes.size)
        put(bodyBytes)
        Log.d(TAG, "writeMessage: pos=%d\tlimit=%d".format(position(), limit()))
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
                crc = crc
            )
        }
    }
}
