package cn.bincker.stream.sound.config

data class AppConfig(
    var port: Int = 12345,
    var privateKey: String = "",
    var devices: List<DeviceConfig> = emptyList(),
    var sampleRate: Int = 48000,
    var bits: Int = 16,
    var channels: Int = 2,
    var format: Int = 1,
    var bufferSize: Int = 4096
)