package cn.bincker.stream.sound.entity

data class OboeTimestamp(
    val framePosition: Long,
    val nanoTime: Long,
)

data class OboeStreamInfo(
    val sampleRate: Int,
    val channelCount: Int,
    val bufferSizeInFrames: Int,
    val bufferCapacityInFrames: Int,
    val framesPerBurst: Int,
    val sharingMode: Int,
    val performanceMode: Int,
)

class OboeAudioPlayer {
    companion object {
        init {
            System.loadLibrary("oboe_player")
        }
    }

    private var nativeHandle: Long = 0L

    fun open(sampleRate: Int, channelCount: Int, bits: Int, preferredBufferFrames: Int): Boolean {
        if (nativeHandle != 0L) {
            release()
        }
        nativeHandle = nativeCreate(sampleRate, channelCount, bits, preferredBufferFrames)
        return nativeHandle != 0L
    }

    fun start(): Boolean = nativeHandle != 0L && nativeStart(nativeHandle)

    fun write(data: ByteArray, offset: Int, size: Int): Int {
        if (nativeHandle == 0L) return -1
        return nativeWrite(nativeHandle, data, offset, size)
    }

    fun getTimestamp(): OboeTimestamp? {
        if (nativeHandle == 0L) return null
        val values = LongArray(2)
        val ok = nativeGetTimestamp(nativeHandle, values)
        if (!ok) return null
        return OboeTimestamp(values[0], values[1])
    }

    fun getStreamInfo(): OboeStreamInfo? {
        if (nativeHandle == 0L) return null
        val values = IntArray(7)
        val ok = nativeGetStreamInfo(nativeHandle, values)
        if (!ok) return null
        return OboeStreamInfo(
            sampleRate = values[0],
            channelCount = values[1],
            bufferSizeInFrames = values[2],
            bufferCapacityInFrames = values[3],
            framesPerBurst = values[4],
            sharingMode = values[5],
            performanceMode = values[6],
        )
    }

    fun stop() {
        if (nativeHandle != 0L) {
            nativeStop(nativeHandle)
        }
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(
        sampleRate: Int,
        channelCount: Int,
        bits: Int,
        preferredBufferFrames: Int,
    ): Long

    private external fun nativeStart(handle: Long): Boolean

    private external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, size: Int): Int

    private external fun nativeGetTimestamp(handle: Long, outTimestamp: LongArray): Boolean

    private external fun nativeGetStreamInfo(handle: Long, outInfo: IntArray): Boolean

    private external fun nativeStop(handle: Long)

    private external fun nativeRelease(handle: Long)
}
