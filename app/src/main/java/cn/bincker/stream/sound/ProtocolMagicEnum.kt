package cn.bincker.stream.sound

import java.nio.ByteBuffer

enum class ProtocolMagicEnum {
    ECDH,
    ECDH_RESPONSE,
    PAIR,
    PAIR_RESPONSE,
    AUTHENTICATION,
    AUTHENTICATION_RESPONSE,
    PLAY,
    PLAY_RESPONSE,
    STOP,
    STOP_RESPONSE,
    ;
    val magic: ByteArray = name.toByteArray()

    companion object {
        // 缓存最大和最小魔数长度
        val maxMagicLen: Int = entries.maxOf { it.magic.size }
        val minMagicLen: Int = entries.minOf { it.magic.size }

        // 按首字节分组并按魔数长度降序排序，用于快速过滤和优先匹配
        private val magicByFirstByte: Map<Byte, List<ProtocolMagicEnum>> =
            entries.groupBy { it.magic[0] }
                .mapValues { (_, list) -> list.sortedByDescending { it.magic.size } }

        /**
         * 高效匹配协议魔数（内部实现滑动窗口搜索）
         *
         * 算法优化：
         * 1. 使用首字节索引快速过滤不可能的候选
         * 2. 直接进行字节级别比较，避免 String 转换
         * 3. 内部实现滑动窗口，自动扫描整个 buffer
         * 4. 优先匹配更长的魔数（更具体的协议）
         *
         * 时间复杂度: O(N × M)
         * - N: buffer 剩余字节数
         * - M: 平均每个首字节对应的候选数量（通常很小）
         *
         * @param buffer 待匹配的字节缓冲区（从当前 position 开始搜索）
         * @return 匹配的枚举值，如果没有匹配则返回 null
         *         匹配成功时，buffer position 会移动到魔数之后
         */
        fun match(buffer: ByteBuffer): ProtocolMagicEnum? {
            val startPosition = buffer.position()
            val limit = buffer.limit()

            // 滑动窗口：尝试每个可能的起始位置
            for (offset in startPosition until limit) {
                if (limit - offset < minMagicLen) break

                val firstByte = buffer.get(offset)

                // 使用首字节索引快速获取候选列表（已按长度降序排序）
                val candidates = magicByFirstByte[firstByte] ?: continue

                // 遍历候选，优先匹配更长的（更具体的）协议
                for (candidate in candidates) {
                    val magicLen = candidate.magic.size
                    if (offset + magicLen > limit) continue

                    // 直接进行字节比较
                    var matched = true
                    for (i in 0 until magicLen) {
                        if (buffer.get(offset + i) != candidate.magic[i]) {
                            matched = false
                            break
                        }
                    }

                    if (matched) {
                        // 匹配成功：将 position 移动到魔数之后
                        buffer.position(offset + magicLen)
                        return candidate
                    }
                }
            }

            // 未找到匹配，保持原始 position 不变
            return null
        }
    }
}