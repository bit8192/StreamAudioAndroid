package cn.bincker.stream.sound.config

data class AppConfig(
    var port: Int = 12345,
    var privateKey: String = "",
    var devices: List<DeviceConfig> = emptyList(),
    var audioBufferSizeBytes: Int = 8192,
    var packetSequenceThreshold: Int = 100,
    var maxAudioQueueSize: Int = 50,
    var oboePreferredBufferFrames: Int = 0,
)
