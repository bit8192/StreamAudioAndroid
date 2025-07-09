package cn.bincker.stream.sound.entity

import java.net.SocketAddress

data class AudioServerInfo(
    var name: String = "",
    var address: SocketAddress
)