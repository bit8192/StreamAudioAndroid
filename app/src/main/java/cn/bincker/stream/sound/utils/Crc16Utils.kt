package cn.bincker.stream.sound.utils
import java.nio.ByteBuffer



private const val POLYNOMIAL: Int = 0x1021
// 预计算的 CRC16 查找表
private val crcTable = IntArray(256) { i ->
    var crc = i shl 8
    repeat(8) {
        crc = if (crc and 0x8000 != 0) {
            (crc shl 1) xor POLYNOMIAL
        } else {
            crc shl 1
        }
    }
    crc and 0xFFFF
}

/**
 * CRC16 校验工具
 *
 * 使用 CRC-16-CCITT 算法（多项式 0x1021，初始值 0xFFFF）
 */
object Crc16 {
    private const val INITIAL_VALUE: Int = 0xFFFF

    /**
     * 计算 CRC16 校验值
     *
     * @param data 数据数组
     * @param offset 起始偏移
     * @param length 数据长度
     * @return CRC16 值（16 位无符号整数）
     */
    fun calculate(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var crc = INITIAL_VALUE
        for (i in offset until offset + length) {
            val index = ((crc shr 8) xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = ((crc shl 8) xor crcTable[index]) and 0xFFFF
        }
        return crc
    }
}

/**
 * 计算 ByteBuffer 的 CRC16 校验值（从当前 position 到 limit）
 *
 * 注意：此方法会保持 buffer 的 position 不变
 *
 * @return CRC16 校验值
 */
fun ByteBuffer.crc16(): Int {
    val originalPosition = position()
    val length = remaining()

    val crc = if (hasArray()) {
        // array-backed buffer，使用数组方式更高效
        Crc16.calculate(array(), arrayOffset() + originalPosition, length)
    } else {
        // 直接从 buffer 读取
        var result = 0xFFFF
        for (i in 0 until length) {
            val b = get(originalPosition + i).toInt() and 0xFF
            val index = ((result shr 8) xor b) and 0xFF
            result = ((result shl 8) xor crcTable[index]) and 0xFFFF
        }
        result
    }

    return crc
}

/**
 * 验证 ByteBuffer 的 CRC16 校验值是否匹配
 *
 * @param expectedCrc16 期望的 CRC16 值
 * @return 校验是否通过
 */
fun ByteBuffer.verifyCrc16(expectedCrc16: Int): Boolean {
    return crc16() == expectedCrc16
}

/**
 * 将 CRC16 值转换为 2 字节的 ByteArray（大端序）
 *
 * @return 包含 CRC16 值的 2 字节数组
 */
fun Int.toCrc16Bytes(): ByteArray {
    return byteArrayOf(
        (this shr 8).toByte(),
        this.toByte()
    )
}

/**
 * 从 ByteBuffer 当前位置读取 CRC16 值（大端序，2 字节），并移动 position
 *
 * @return CRC16 值
 */
fun ByteBuffer.getCrc16(): Int {
    return ((get().toInt() and 0xFF) shl 8) or
           (get().toInt() and 0xFF)
}

/**
 * 从 ByteBuffer 指定位置读取 CRC16 值（大端序，2 字节），不改变 position
 *
 * @param offset 读取位置的偏移量
 * @return CRC16 值
 */
fun ByteBuffer.getCrc16(offset: Int): Int {
    return ((get(offset).toInt() and 0xFF) shl 8) or
           (get(offset + 1).toInt() and 0xFF)
}

/**
 * 向 ByteBuffer 当前位置写入 CRC16 值（大端序，2 字节），并移动 position
 *
 * @param crc16 要写入的 CRC16 值
 */
fun ByteBuffer.putCrc16(crc16: Int): ByteBuffer {
    put((crc16 shr 8).toByte())
    put(crc16.toByte())
    return this
}

/**
 * 向 ByteBuffer 指定位置写入 CRC16 值（大端序，2 字节），不改变 position
 *
 * @param offset 写入位置的偏移量
 * @param crc16 要写入的 CRC16 值
 */
fun ByteBuffer.putCrc16(offset: Int, crc16: Int): ByteBuffer {
    put(offset, (crc16 shr 8).toByte())
    put(offset + 1, crc16.toByte())
    return this
}

/**
 * 计算0位置到当前位置的 CRC16值 到当前位置，并移动 position
 */
fun ByteBuffer.putCrc16(): ByteBuffer {
    val pos = position()
    val limit = limit()
    flip()
    val value = crc16()
    position(pos)
    limit(limit)
    putCrc16(value)
    return this
}
