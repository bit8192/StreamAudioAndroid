package cn.bincker.stream.sound.entity

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import cn.bincker.stream.sound.config.AudioEncryptionMethod
import cn.bincker.stream.sound.utils.aes128gcmDecrypt
import cn.bincker.stream.sound.utils.aes256gcmDecrypt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import android.media.AudioTimestamp
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class UdpAudioReceiver(
    private val serverAddress: InetAddress,
    private val clientPort: Int,
    preboundSocket: DatagramSocket? = null,
    private val udpAudioKey: ByteArray,
    private val audioEncryption: AudioEncryptionMethod,
    private val sampleRate: Int,
    private val bits: Int,
    private val channels: Int,
    private val format: Int,
    audioBufferSizeBytes: Int = DEFAULT_AUDIO_BUFFER_SIZE,
    sequenceThreshold: Int = DEFAULT_SEQUENCE_THRESHOLD,
    maxQueueSize: Int = DEFAULT_MAX_QUEUE_SIZE,
    oboePreferredBufferFrames: Int = 0,
    serverToClientOffsetNs: Long = 0L,
    lastSyncRttNs: Long = -1L,
) {
    companion object {
        private const val TAG = "UdpAudioReceiver"
        private const val UDP_BUFFER_SIZE = 8192
        const val DEFAULT_AUDIO_BUFFER_SIZE = 8192
        const val DEFAULT_SEQUENCE_THRESHOLD = 100
        const val DEFAULT_MAX_QUEUE_SIZE = 50
        private const val HEARTBEAT_INTERVAL_MS = 1000L
    }

    private var datagramSocket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null
    private var oboePlayer: OboeAudioPlayer? = null
    private var useOboe = false
    private var receiverJob: Job? = null
    private var audioPlayerJob: Job? = null

    private val isReceiving = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)

    // Audio data queue (thread-safe)
    private data class AudioPacket(
        val data: ByteArray,
        val captureTimeClientNs: Long,
        val receiveTimeClientNs: Long,
        val captureTimeValid: Boolean,
    )

    private val audioQueue = mutableListOf<AudioPacket>()
    private val queueLock = Any()
    private val maxQueueSizeLimit = AtomicInteger(maxQueueSize.coerceIn(1, 100))
    private val oboePreferredFrames = AtomicInteger(oboePreferredBufferFrames.coerceIn(0, 4096))

    // Sequence tracking
    private val expectedSequence = AtomicLong(0)
    private var lastPacketTime = 0L
    private val lastHeartbeatSentMs = AtomicLong(0L)
    private val heartbeatPayload = byteArrayOf(0x41, 0x43, 0x4B, 0x31) // "ACK1"

    private val serverToClientOffsetNs = AtomicLong(serverToClientOffsetNs)
    private val lastSyncRttNs = AtomicLong(lastSyncRttNs)
    private val endToEndLatencyNs = AtomicLong(-1L)
    private val networkLatencyNs = AtomicLong(-1L)
    private val playbackBufferLatencyNs = AtomicLong(-1L)
    private val decryptLatencyNs = AtomicLong(-1L)
    private val totalPackets = AtomicLong(0L)
    private val shortPackets = AtomicLong(0L)
    private val decryptErrors = AtomicLong(0L)
    private val tooOldPackets = AtomicLong(0L)
    private val lastPacketLength = AtomicLong(0L)
    private val lastDecryptedLength = AtomicLong(0L)
    private val lastAlignedLength = AtomicLong(0L)
    private val lastWrittenLength = AtomicLong(0L)
    private var consecutiveTooOld = 0
    private val audioBufferSizeBytes = audioBufferSizeBytes.coerceAtLeast(256)
    private val sequenceThresholdLimit = AtomicInteger(sequenceThreshold.coerceAtLeast(0))
    private val bytesPerFrame = channels * (bits / 8)
    @Volatile
    private var outputMethod: PlaybackOutputMethod = PlaybackOutputMethod.UNKNOWN

    fun setServerToClientOffsetNs(offsetNs: Long) {
        serverToClientOffsetNs.set(offsetNs)
    }

    fun setLastSyncRttNs(rttNs: Long) {
        lastSyncRttNs.set(rttNs)
    }

    fun getEndToEndLatencyMs(): Long {
        val v = endToEndLatencyNs.get()
        return if (v >= 0) v / 1_000_000L else -1L
    }

    fun getNetworkLatencyMs(): Long {
        val v = networkLatencyNs.get()
        return if (v >= 0) v / 1_000_000L else -1L
    }

    fun getPlaybackBufferLatencyMs(): Long {
        val v = playbackBufferLatencyNs.get()
        return if (v >= 0) v / 1_000_000L else -1L
    }

    fun getDecryptLatencyMs(): Long {
        val v = decryptLatencyNs.get()
        return if (v >= 0) v / 1_000_000L else -1L
    }

    fun getSyncRttMs(): Long {
        val v = lastSyncRttNs.get()
        return if (v >= 0) v / 1_000_000L else -1L
    }

    fun getOutputMethod(): PlaybackOutputMethod = outputMethod

    fun setMaxQueueSize(maxQueueSize: Int) {
        val safeSize = maxQueueSize.coerceIn(1, 100)
        maxQueueSizeLimit.set(safeSize)
        synchronized(queueLock) {
            while (audioQueue.size > safeSize) {
                audioQueue.removeFirstOrNull()
            }
        }
    }

    fun setSequenceThreshold(sequenceThreshold: Int) {
        val safeThreshold = sequenceThreshold.coerceAtLeast(0)
        sequenceThresholdLimit.set(safeThreshold)
    }

    fun setOboePreferredBufferFrames(preferredFrames: Int) {
        val safeFrames = preferredFrames.coerceIn(0, 4096)
        oboePreferredFrames.set(safeFrames)
        if (useOboe) {
            val targetFrames = resolveOboePreferredFrames()
            oboePlayer?.setBufferSizeInFrames(targetFrames)
        }
    }

    init {
        datagramSocket = preboundSocket
    }

    suspend fun start(scope: CoroutineScope) {
        withContext(Dispatchers.IO) {
            if (isReceiving.get()) {
                Log.w(TAG, "UDP receiver already running")
                return@withContext
            }

            try {
                // Create UDP socket (or reuse pre-bound one)
                if (datagramSocket == null) {
                    datagramSocket = DatagramSocket(clientPort)
                }
                datagramSocket?.receiveBufferSize = maxOf(datagramSocket?.receiveBufferSize ?: 0, 1 shl 20)

                // Setup audio output
                setupAudioPlayer()

                isReceiving.set(true)
                isPlaying.set(true)

                // Start receiving packets
                receiverJob = scope.launch(Dispatchers.IO) {
                    receiveLoop()
                }

                // Start audio playback
                audioPlayerJob = scope.launch(Dispatchers.IO) {
                    playbackLoop()
                }

                Log.i(TAG, "UDP audio receiver started on port $clientPort")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start UDP receiver", e)
                cleanup()
                throw e
            }
        }
    }

    suspend fun stop() {
        withContext(Dispatchers.IO) {
            if (!isReceiving.get()) {
                return@withContext
            }

            Log.i(TAG, "Stopping UDP audio receiver")

            isReceiving.set(false)
            isPlaying.set(false)
            try {
                datagramSocket?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing UDP socket during stop", e)
            }

            // Cancel jobs
            receiverJob?.cancel()
            audioPlayerJob?.cancel()

            // Wait for jobs to complete
            receiverJob?.join()
            audioPlayerJob?.join()

            cleanup()

            Log.i(TAG, "UDP audio receiver stopped")
        }
    }

    private fun setupAudioPlayer() {
        if (setupOboePlayer()) {
            useOboe = true
            audioTrack = null
            outputMethod = PlaybackOutputMethod.OBOE
            val info = oboePlayer?.getStreamInfo()
            Log.i(
                TAG,
                "Audio output: Oboe, sr=${info?.sampleRate}, ch=${info?.channelCount}, " +
                    "bufFrames=${info?.bufferSizeInFrames}, capFrames=${info?.bufferCapacityInFrames}, " +
                    "burst=${info?.framesPerBurst}, share=${info?.sharingMode}, perf=${info?.performanceMode}"
            )
            return
        }
        useOboe = false
        outputMethod = PlaybackOutputMethod.AUDIO_TRACK
        val channelConfig = when (channels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> throw Exception("Unsupported channel count: $channels")
        }

        val encoding = when (bits) {
            8 -> AudioFormat.ENCODING_PCM_8BIT
            16 -> AudioFormat.ENCODING_PCM_16BIT
            24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
            32 -> AudioFormat.ENCODING_PCM_32BIT
            else -> throw Exception("Unsupported bit depth: $bits")
        }

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSize = maxOf(minBufferSize, audioBufferSizeBytes)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        Log.i(
            TAG,
            "Audio output: AudioTrack, sr=$sampleRate, bits=$bits, ch=$channels, " +
                "bufferSizeBytes=$bufferSize, bufFrames=${audioTrack?.bufferSizeInFrames}, " +
                "capFrames=${audioTrack?.bufferCapacityInFrames}, perf=${audioTrack?.performanceMode}"
        )
    }

    private fun setupOboePlayer(): Boolean {
        if (bits == 8) return false
        val preferredBufferFrames = resolveOboePreferredFrames()
        val player = OboeAudioPlayer()
        if (!player.open(sampleRate, channels, bits, preferredBufferFrames)) {
            player.release()
            return false
        }
        if (!player.start()) {
            player.release()
            return false
        }
        oboePlayer = player
        return true
    }

    private fun resolveOboePreferredFrames(): Int {
        val preferred = oboePreferredFrames.get()
        if (preferred > 0) return preferred
        if (bytesPerFrame <= 0) return 0
        return (audioBufferSizeBytes / bytesPerFrame).coerceAtLeast(0)
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(UDP_BUFFER_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)

        try {
            while (isReceiving.get()) {
                datagramSocket?.receive(packet)
                val receiveTimeNs = System.nanoTime()

                if (packet.length >= 4) {
                    val sequence = processPacket(packet.data, packet.length, receiveTimeNs)
                    if (sequence != null) {
                        maybeSendHeartbeat(packet, sequence)
                    }
                    lastPacketTime = System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            if (isReceiving.get()) {
                Log.e(TAG, "Error in receive loop", e)
            }
        }
    }

    private fun processPacket(data: ByteArray, length: Int, receiveTimeNs: Long): Int? {
        // Packet: [seq(4)] + [capture_time_ns(8)] + [encrypted_audio]
        if (length < 12) {
            shortPackets.incrementAndGet()
            Log.w(TAG, "Packet too short: $length bytes")
            return null
        }
        totalPackets.incrementAndGet()
        lastPacketLength.set(length.toLong())

        try {
            // Parse sequence number (big-endian)
            val sequenceNumber = ((data[0].toInt() and 0xFF) shl 24) or
                               ((data[1].toInt() and 0xFF) shl 16) or
                               ((data[2].toInt() and 0xFF) shl 8) or
                               (data[3].toInt() and 0xFF)

            // Parse capture timestamp (big-endian uint64)
            var captureTimeServerNs = 0L
            for (i in 0 until 8) {
                captureTimeServerNs = (captureTimeServerNs shl 8) or (data[4 + i].toLong() and 0xFFL)
            }
            val captureTimeClientNs = if (lastSyncRttNs.get() >= 0) {
                captureTimeServerNs + serverToClientOffsetNs.get()
            } else {
                0L
            }
            val normalizedCaptureTimeNs = normalizeCaptureTime(captureTimeClientNs, receiveTimeNs)
            val captureTimeValid = captureTimeClientNs > 0L && normalizedCaptureTimeNs == captureTimeClientNs

            // Check sequence ordering
            var expected = expectedSequence.get()
            if (expected == 0L && totalPackets.get() == 1L) {
                expectedSequence.set(sequenceNumber.toLong())
                expected = sequenceNumber.toLong()
            }
            val threshold = sequenceThresholdLimit.get()
            if (sequenceNumber < expected - threshold) {
                // Too old packet, ignore
                tooOldPackets.incrementAndGet()
                consecutiveTooOld++
                if (consecutiveTooOld >= 50) {
                    Log.w(TAG, "Resync sequence: expected=$expected, got=$sequenceNumber")
                    expectedSequence.set(sequenceNumber.toLong())
                    consecutiveTooOld = 0
                }
                return null
            }
            consecutiveTooOld = 0

            if (sequenceNumber > expected + threshold) {
                Log.w(TAG, "Large sequence gap: expected=$expected, got=$sequenceNumber")
                // Reset expected sequence
                expectedSequence.set((sequenceNumber + 1).toLong())
            }

            // Decrypt audio data
            val encryptedAudio = data.copyOfRange(12, length)
            val decryptStartNs = System.nanoTime()
            val decryptedAudio = decryptAudioData(encryptedAudio, sequenceNumber)
            val decryptElapsedNs = System.nanoTime() - decryptStartNs
            if (decryptElapsedNs >= 0) {
                decryptLatencyNs.set(decryptElapsedNs)
            }
            lastDecryptedLength.set(decryptedAudio.size.toLong())

            if (sequenceNumber >= expected) {
                expectedSequence.set((sequenceNumber + 1).toLong())
            }
            enqueueAudioPacket(decryptedAudio, normalizedCaptureTimeNs, receiveTimeNs, captureTimeValid)

            return sequenceNumber
        } catch (e: Exception) {
            decryptErrors.incrementAndGet()
            Log.e(TAG, "Error processing packet", e)
        }
        return null
    }

    private fun maybeSendHeartbeat(packet: DatagramPacket, sequenceNumber: Int) {
        val nowMs = System.currentTimeMillis()
        val lastMs = lastHeartbeatSentMs.get()
        if (nowMs - lastMs < HEARTBEAT_INTERVAL_MS) return

        val socket = datagramSocket ?: return
        try {
            val payload = heartbeatPayload.copyOf()
            payload[0] = 0x41
            payload[1] = 0x43
            payload[2] = 0x4B
            payload[3] = (sequenceNumber and 0xFF).toByte()
            val response = DatagramPacket(payload, payload.size, packet.address, packet.port)
            socket.send(response)
            lastHeartbeatSentMs.set(nowMs)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send UDP heartbeat", e)
        }
    }

    private fun enqueueAudioPacket(
        data: ByteArray,
        captureTimeClientNs: Long,
        receiveTimeNs: Long,
        captureTimeValid: Boolean,
    ) {
        synchronized(queueLock) {
            audioQueue.add(AudioPacket(data, captureTimeClientNs, receiveTimeNs, captureTimeValid))

            // Limit queue size to prevent memory overflow
            val limit = maxQueueSizeLimit.get()
            while (audioQueue.size > limit) {
                audioQueue.removeFirstOrNull()
            }
        }
    }

    private fun normalizeCaptureTime(rawCaptureTimeNs: Long, receiveTimeNs: Long): Long {
        if (rawCaptureTimeNs <= 0L) {
            return receiveTimeNs
        }
        if (rawCaptureTimeNs > receiveTimeNs + 5_000_000_000L) {
            return receiveTimeNs
        }
        if (receiveTimeNs - rawCaptureTimeNs > 60_000_000_000L) {
            return receiveTimeNs
        }
        return rawCaptureTimeNs
    }

    private fun buildUdpAudioIv(sequenceNumber: Int): ByteArray {
        val iv = ByteArray(12)
        iv[0] = (sequenceNumber shr 24).toByte()
        iv[1] = (sequenceNumber shr 16).toByte()
        iv[2] = (sequenceNumber shr 8).toByte()
        iv[3] = sequenceNumber.toByte()
        System.arraycopy(udpAudioKey, 0, iv, 4, 8)
        return iv
    }

    private fun decryptAudioData(encryptedData: ByteArray, sequenceNumber: Int): ByteArray {
        return when (audioEncryption) {
            AudioEncryptionMethod.NONE -> encryptedData
            AudioEncryptionMethod.XOR_256 -> {
                val decrypted = encryptedData.clone()
                for (i in decrypted.indices) {
                    decrypted[i] = (decrypted[i].toInt() xor udpAudioKey[i % udpAudioKey.size].toInt()).toByte()
                }
                decrypted
            }
            AudioEncryptionMethod.AES128GCM -> {
                val iv = buildUdpAudioIv(sequenceNumber)
                val key = udpAudioKey.copyOfRange(0, 16)
                aes128gcmDecrypt(encryptedData, key, iv)
            }
            AudioEncryptionMethod.AES256GCM -> {
                val iv = buildUdpAudioIv(sequenceNumber)
                aes256gcmDecrypt(encryptedData, udpAudioKey, iv)
            }
        }
    }

    private suspend fun playbackLoop() {
        val audioTrack = this.audioTrack
        val oboePlayer = this.oboePlayer
        val usingOboe = useOboe && oboePlayer != null
        if (!usingOboe && audioTrack == null) return
        val bytesPerFrame = channels * (bits / 8)
        if (bytesPerFrame <= 0) return

        var framesWrittenTotal = 0L
        val timestamp = AudioTimestamp()

        try {
            if (usingOboe) {
                oboePlayer.start()
            } else {
                audioTrack?.play()
            }

            var lastStatsLogMs = SystemClock.elapsedRealtime()
            var pendingData: ByteArray? = null
            var pendingOffset = 0
            var pendingCaptureTimeNs = 0L
            var pendingReceiveTimeNs = 0L
            var pendingCaptureTimeValid = false
            var pendingFirstFrame = true
            val outputLabel = if (usingOboe) "Oboe" else "AudioTrack"

            while (isPlaying.get()) {
                val nowMs = SystemClock.elapsedRealtime()
                if (nowMs - lastStatsLogMs >= 1000) {
                    val queueSize = synchronized(queueLock) { audioQueue.size }
                    Log.i(
                        TAG,
                        "audio stats: output=$outputLabel, packets=${totalPackets.get()}, short=${shortPackets.get()}, decryptErr=${decryptErrors.get()}, " +
                            "tooOld=${tooOldPackets.get()}, lastLen=${lastPacketLength.get()}, decLen=${lastDecryptedLength.get()}, " +
                            "aligned=${lastAlignedLength.get()}, written=${lastWrittenLength.get()}, queue=$queueSize"
                    )
                    lastStatsLogMs = nowMs
                }

                if (pendingData == null) {
                    val packet: AudioPacket? = synchronized(queueLock) {
                        if (audioQueue.isNotEmpty()) {
                            audioQueue.removeFirstOrNull()
                        } else {
                            null
                        }
                    }
                    if (packet != null) {
                        pendingData = packet.data
                        pendingOffset = 0
                        pendingCaptureTimeNs = packet.captureTimeClientNs
                        pendingReceiveTimeNs = packet.receiveTimeClientNs
                        pendingCaptureTimeValid = packet.captureTimeValid
                        pendingFirstFrame = true
                    }
                }

                if (pendingData != null) {
                    val audioData = pendingData ?: break
                    val captureTimeClientNs = pendingCaptureTimeNs
                    val receiveTimeClientNs = pendingReceiveTimeNs
                    val captureTimeValid = pendingCaptureTimeValid

                    val firstFrameIndex = framesWrittenTotal
                    val alignedSize = audioData.size - (audioData.size % bytesPerFrame)
                    lastAlignedLength.set(alignedSize.toLong())
                    if (alignedSize <= 0) {
                        pendingData = null
                        continue
                    }
                    if (!usingOboe) {
                        val track = audioTrack
                        if (track != null && track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            try {
                                track.play()
                            } catch (e: Exception) {
                                Log.w(TAG, "AudioTrack play failed", e)
                            }
                        }
                    }
                    var offset = pendingOffset
                    var totalWritten = 0
                    while (offset < alignedSize) {
                        val bytesWritten = if (usingOboe) {
                            oboePlayer?.write(audioData, offset, alignedSize - offset) ?: -1
                        } else {
                            audioTrack?.write(
                                audioData,
                                offset,
                                alignedSize - offset,
                                AudioTrack.WRITE_BLOCKING
                            ) ?: -1
                        }
                        if (bytesWritten < 0) {
                            Log.w(TAG, "Audio write failed: $bytesWritten")
                            break
                        }
                        if (bytesWritten == 0) {
                            kotlinx.coroutines.delay(5)
                            break
                        }
                        offset += bytesWritten
                        totalWritten += bytesWritten
                    }

                    if (totalWritten <= 0) {
                        continue
                    }
                    lastWrittenLength.set(totalWritten.toLong())

                    val framesWritten = totalWritten / bytesPerFrame
                    framesWrittenTotal += framesWritten

                    if (framesWritten > 0 && pendingFirstFrame) {
                        val timestampResult = if (usingOboe) {
                            oboePlayer?.getTimestamp()?.let { it.framePosition to it.nanoTime }
                        } else {
                            val hasTs = audioTrack?.getTimestamp(timestamp) == true
                            if (hasTs) timestamp.framePosition to timestamp.nanoTime else null
                        }
                        if (timestampResult != null) {
                            val (framePosition, timeNs) = timestampResult
                            val frameDelta = firstFrameIndex - framePosition
                            if (frameDelta >= 0) {
                                val playTimeNs = timeNs + (frameDelta * 1_000_000_000L) / sampleRate
                                val bufferLatencyNs = playTimeNs - receiveTimeClientNs
                                if (bufferLatencyNs >= 0) {
                                    playbackBufferLatencyNs.set(bufferLatencyNs)
                                }
                                if (captureTimeValid) {
                                    val networkLatency = receiveTimeClientNs - captureTimeClientNs
                                    val totalLatency = playTimeNs - captureTimeClientNs
                                    if (networkLatency >= 0) {
                                        networkLatencyNs.set(networkLatency)
                                    }
                                    if (totalLatency >= 0) {
                                        endToEndLatencyNs.set(totalLatency)
                                    }
                                } else {
                                    networkLatencyNs.set(-1L)
                                    endToEndLatencyNs.set(-1L)
                                }
                            }
                        }
                    }
                    pendingFirstFrame = false
                    if (offset >= alignedSize) {
                        pendingData = null
                        pendingOffset = 0
                    } else {
                        pendingOffset = offset
                    }
                } else {
                    kotlinx.coroutines.delay(10)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in playback loop", e)
        } finally {
            if (usingOboe) {
                try {
                    oboePlayer?.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping Oboe", e)
                }
            } else {
                try {
                    audioTrack?.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping AudioTrack", e)
                }
            }
        }
    }

    private fun cleanup() {
        try {
            datagramSocket?.close()
            datagramSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing datagram socket", e)
        }

        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack", e)
        }
        try {
            oboePlayer?.release()
            oboePlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Oboe", e)
        }
        useOboe = false
        outputMethod = PlaybackOutputMethod.UNKNOWN

        synchronized(queueLock) {
            audioQueue.clear()
        }
    }

    fun getStats(): String {
        val queueSize = synchronized(queueLock) { audioQueue.size }
        val timeSinceLastPacket = if (lastPacketTime > 0) {
            System.currentTimeMillis() - lastPacketTime
        } else {
            -1
        }

        val e2eLatencyMs = getEndToEndLatencyMs()
        val networkLatencyMs = getNetworkLatencyMs()
        val bufferLatencyMs = getPlaybackBufferLatencyMs()
        val decryptLatencyMs = getDecryptLatencyMs()
        val rttMs = getSyncRttMs()

        return "Queue: $queueSize, LastPacket: ${timeSinceLastPacket}ms ago, Expected: ${expectedSequence.get()}, " +
            "E2E: ${e2eLatencyMs}ms, Net: ${networkLatencyMs}ms, Buffer: ${bufferLatencyMs}ms, " +
            "Decrypt: ${decryptLatencyMs}ms, RTT: ${rttMs}ms"
    }
}
