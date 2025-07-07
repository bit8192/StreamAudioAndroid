package cn.bincker.stream.sound.entity

import java.net.InetAddress
import java.util.UUID

data class AudioServerInfo(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var address: InetAddress
)