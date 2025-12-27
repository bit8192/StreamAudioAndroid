package cn.bincker.stream.sound.config

data class AppConfig(
    var port: Int = 12345,
    var privateKey: String = "",
    var devices: List<DeviceConfig> = emptyList()
)