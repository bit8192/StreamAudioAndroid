package cn.bincker.stream.sound.entity

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import cn.bincker.stream.sound.utils.hMacSha256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

class UdpAudioReceiver(
    private val serverAddress: InetAddress,
    private val clientPort: Int,
    private val udpAudioKey: ByteArray,
    private val sampleRate: Int,
    private val bits: Int,
    private val channels: Int,
    private val format: Int
) {
    companion object {
        private const val TAG = "UdpAudioReceiver"
        private const val UDP_BUFFER_SIZE = 2048
        private const val AUDIO_BUFFER_SIZE = 8192
        private const val SEQUENCE_THRESHOLD = 100 // 容忍的丢包数量
    }

    private var datagramSocket: DatagramSocket? = null
    private var audioTrack: AudioTrack? = null
    private var receiverJob: Job? = null
    private var audioPlayerJob: Job? = null

    private val isReceiving = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)

    // Audio data queue (thread-safe)
    private val audioQueue = mutableListOf<ByteArray>()
    private val queueLock = Any()

    // Sequence tracking
    private val expectedSequence = AtomicLong(0)
    private var lastPacketTime = 0L

    suspend fun start(scope: CoroutineScope) {
        withContext(Dispatchers.IO) {
            if (isReceiving.get()) {
                Log.w(TAG, "UDP receiver already running")
                return@withContext
            }

            try {
                // Create UDP socket
                datagramSocket = DatagramSocket(clientPort)

                // Setup AudioTrack
                setupAudioTrack()

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

    private fun setupAudioTrack() {
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
        val bufferSize = maxOf(minBufferSize, AUDIO_BUFFER_SIZE)

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

        Log.i(TAG, "AudioTrack setup: sr=$sampleRate, bits=$bits, ch=$channels, bufferSize=$bufferSize")
    }

    private suspend fun receiveLoop() {
        val buffer = ByteArray(UDP_BUFFER_SIZE)
        val packet = DatagramPacket(buffer, buffer.size)

        try {
            while (isReceiving.get()) {
                datagramSocket?.receive(packet)

                if (packet.length >= 4) {
                    processPacket(packet.data, packet.length)
                    lastPacketTime = System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            if (isReceiving.get()) {
                Log.e(TAG, "Error in receive loop", e)
            }
        }
    }

    private fun processPacket(data: ByteArray, length: Int) {
        if (length < 4) {
            Log.w(TAG, "Packet too short: $length bytes")
            return
        }

        try {
            // Parse sequence number (big-endian)
            val sequenceNumber = ((data[0].toInt() and 0xFF) shl 24) or
                               ((data[1].toInt() and 0xFF) shl 16) or
                               ((data[2].toInt() and 0xFF) shl 8) or
                               (data[3].toInt() and 0xFF)

            // Check sequence ordering
            val expected = expectedSequence.get()
            if (sequenceNumber < expected - SEQUENCE_THRESHOLD) {
                // Too old packet, ignore
                return
            }

            if (sequenceNumber > expected + SEQUENCE_THRESHOLD) {
                Log.w(TAG, "Large sequence gap: expected=$expected, got=$sequenceNumber")
                // Reset expected sequence
                expectedSequence.set((sequenceNumber + 1).toLong())
            } else if (sequenceNumber >= expected) {
                expectedSequence.set((sequenceNumber + 1).toLong())
            }

            // Decrypt audio data
            val encryptedAudio = data.copyOfRange(4, length)
            val decryptedAudio = decryptAudioData(encryptedAudio)

            // Add to audio queue
            synchronized(queueLock) {
                audioQueue.add(decryptedAudio)

                // Limit queue size to prevent memory overflow
                while (audioQueue.size > 50) {
                    audioQueue.removeFirstOrNull()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing packet", e)
        }
    }

    private fun decryptAudioData(encryptedData: ByteArray): ByteArray {
        // Simple XOR decryption (matching C++ implementation)
        val decrypted = encryptedData.clone()
        for (i in decrypted.indices) {
            decrypted[i] = (decrypted[i].toInt() xor udpAudioKey[i % udpAudioKey.size].toInt()).toByte()
        }
        return decrypted
    }

    private suspend fun playbackLoop() {
        val audioTrack = this.audioTrack ?: return

        try {
            audioTrack.play()

            var silenceStart = 0L
            var isInSilence = false

            while (isPlaying.get()) {
                val audioData: ByteArray? = synchronized(queueLock) {
                    if (audioQueue.isNotEmpty()) {
                        audioQueue.removeFirstOrNull()
                    } else {
                        null
                    }
                }

                if (audioData != null) {
                    // Check for silence (to prevent system muting after 60s of silence)
                    val isSilent = audioData.all { it.toInt() == 0 }

                    if (isSilent) {
                        if (!isInSilence) {
                            silenceStart = System.currentTimeMillis()
                            isInSilence = true
                        } else if (System.currentTimeMillis() - silenceStart > 5000) {
                            // Skip silence after 5 seconds to prevent system muting
                            kotlinx.coroutines.delay(10)
                            continue
                        }
                    } else {
                        isInSilence = false
                    }

                    // Write audio data to track
                    val bytesWritten = audioTrack.write(audioData, 0, audioData.size, AudioTrack.WRITE_NON_BLOCKING)

                    if (bytesWritten < 0) {
                        Log.w(TAG, "AudioTrack write failed: $bytesWritten")
                    }
                } else {
                    // No audio data available, wait briefly
                    kotlinx.coroutines.delay(10)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in playback loop", e)
        } finally {
            try {
                audioTrack.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping AudioTrack", e)
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

        return "Queue: $queueSize, LastPacket: ${timeSinceLastPacket}ms ago, Expected: ${expectedSequence.get()}"
    }
}