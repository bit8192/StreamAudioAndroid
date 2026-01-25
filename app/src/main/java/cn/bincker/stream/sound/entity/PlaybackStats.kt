package cn.bincker.stream.sound.entity

enum class PlaybackOutputMethod(val label: String) {
    OBOE("Oboe"),
    AUDIO_TRACK("AudioTrack"),
    UNKNOWN("未知"),
}

data class PlaybackStats(
    val udpPort: Int?,
    val endToEndLatencyMs: Long?,
    val networkLatencyMs: Long?,
    val bufferLatencyMs: Long?,
    val decryptLatencyMs: Long?,
    val syncRttMs: Long?,
    val outputMethod: PlaybackOutputMethod,
)
