package cn.bincker.stream.sound.entity

import cn.bincker.stream.sound.ProtocolMagicEnum

data class Message<T>(
    val magic: ProtocolMagicEnum,
    val version: Int,
    val queueNum: Int,
    val packLength: Int,
    val data: T,
    val crc: Int
)
