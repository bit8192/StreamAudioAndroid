package cn.bincker.stream.sound.config

data class DeviceConfig(
    var name: String = "",
    var address: String = "",
    var publicKey: String = "",
    var autoPlay: Boolean = true
)