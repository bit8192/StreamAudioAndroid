package cn.bincker.stream.sound

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.IBinder
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import kotlin.concurrent.thread

class AudioService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var playJob: Job? = null
    private var play = true

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationId = "stream_audio_notification_channel"
        val channel = NotificationChannel(notificationId, "stream audio notification channel", NotificationManager.IMPORTANCE_LOW)
        getSystemService<NotificationManager>()!!.createNotificationChannel(channel)
        startForeground(1, Notification.Builder(this, notificationId).setContentTitle(getString(R.string.app_name)).setContentText("playing...").setSmallIcon(R.drawable.ic_launcher_foreground).build())
        val cmd = intent?.getIntExtra("cmd", -1)
        Log.d("AudioService.onStartCommand", "cmd=$cmd")
        if (cmd == 0){
            play = true
            start()
        }else if(cmd == 1){
            play = false
            playJob?.cancel()
            playJob = null
            Log.d("AudioService.onStartCommand", "stopped")
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun start(){
        playJob = scope.launch {
            suspendCancellableCoroutine<Unit> { coroutine ->
                var audioTrack: AudioTrack? = null
                var channel: SocketChannel? = null
                coroutine.invokeOnCancellation {
                    Log.d("AudioService.start", "do cancel")
                    channel?.close()
                    Log.d("AudioService.start", "do cancel channel completed")
                    audioTrack?.stop()
                    Log.d("AudioService.start", "do cancel audio completed")
                }
                SocketChannel.open(InetSocketAddress("192.168.1.12", 8888)).use { lChannel ->
                    channel = lChannel
                    var byteBuffer: ByteBuffer =
                        ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                    while (play && byteBuffer.position() < byteBuffer.capacity()) {
                        if (lChannel.read(byteBuffer) == -1) {
                            play = false
                            return@use
                        }
                    }
                    byteBuffer.flip()
                    val sampleRate = byteBuffer.getInt()
                    val bitsPerSample = byteBuffer.getShort().toInt()
                    val formatTag = byteBuffer.getShort().toInt()
                    val channels = byteBuffer.getShort().toInt()
                    Log.d(
                        "AudioService.start",
                        "sampleRate=${sampleRate}\tbitesPerSample=${bitsPerSample}\tformatTag=${formatTag}\tchannels=${channels}"
                    )
                    if (sampleRate < 8000 || sampleRate > 192000) throw Exception("unsupported sample rate: $sampleRate")
                    if (formatTag != 1) throw Exception("unsupported format: $formatTag")
                    val channelConfig = when (channels) {
                        1 -> AudioFormat.CHANNEL_OUT_MONO
                        2 -> AudioFormat.CHANNEL_OUT_STEREO
                        else -> throw Exception("unsupported channels: $formatTag")
                    }
                    val encoding = when (bitsPerSample) {
                        8 -> AudioFormat.ENCODING_PCM_8BIT
                        16 -> AudioFormat.ENCODING_PCM_16BIT
                        24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                        32 -> AudioFormat.ENCODING_PCM_32BIT
                        else -> throw throw Exception("unsupported bits peer sample: $bitsPerSample")
                    }
                    val lAudioTrack = AudioTrack(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                            .build(),
                        AudioFormat.Builder().setSampleRate(sampleRate)
                            .setEncoding(encoding)
                            .setChannelMask(channelConfig)
                            .build(),
                        AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding),
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                    )
                    audioTrack = lAudioTrack
                    Log.d(
                        "AudioService.start",
                        "buffer size = ${
                            AudioTrack.getMinBufferSize(
                                sampleRate,
                                channelConfig,
                                encoding
                            )
                        }, ${lAudioTrack.bufferSizeInFrames}"
                    )
//                    lAudioTrack.per = AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                    lAudioTrack.play()
                    byteBuffer = ByteBuffer.allocate(lAudioTrack.bufferSizeInFrames * 2)
                    try {
                        var total = 0L
                        var times = 0L
                        while (play && lChannel.read(byteBuffer) != -1) {
                            byteBuffer.flip()
                            if (byteBuffer.hasRemaining()) {
                                total += byteBuffer.remaining()
                                if (times++ % 100000 == 0L){
                                    Log.d("AudioService.start", "agv read data size: $times = ${total / times}")
                                }
                                lAudioTrack.write(byteBuffer, byteBuffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                            }
                            byteBuffer.compact()
                        }
                    }catch (e: Exception){
                        if (play){
                            Log.d("AudioService.start", "receive stream error", e)
                        }
                    }
                }
            }
        }
    }
}