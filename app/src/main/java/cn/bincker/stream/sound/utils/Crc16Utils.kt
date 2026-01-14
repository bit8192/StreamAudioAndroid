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
 * 计算 ByteBuffer 的 CRC16 校验值（从 0 到 当前 position）
 *
 * 注意：此方法会保持 buffer 的 position 不变
 *
 * @return CRC16 校验值
 */
fun ByteBuffer.crc16() = Crc16.calculate(array(), arrayOffset(), position())

/**
 * 验证 ByteBuffer 的 CRC16 校验值是否匹配
 *
 * @param expectedCrc16 期望的 CRC16 值
 * @return 校验是否通过
 */
fun ByteBuffer.verifyCrc16(expectedCrc16: Int = peekCrc16()): Boolean {
    return crc16() == expectedCrc16
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
 * 从 ByteBuffer 当前位置读取 CRC16 值（大端序，2 字节），不移动 position
 *
 * @return CRC16 值
 */
fun ByteBuffer.peekCrc16(): Int {
    return ((get(position()).toInt() and 0xFF) shl 8) or
            (get(position() + 1).toInt() and 0xFF)
}

/**
 * 向 ByteBuffer 当前位置写入 CRC16 值（大端序，2 字节），并移动 position
 *
 * @param crc16 要写入的 CRC16 值
 */
fun ByteBuffer.putCrc16(crc16: Int = crc16()): ByteBuffer {
    put((crc16 shr 8).toByte())
    put(crc16.toByte())
    return this
}
