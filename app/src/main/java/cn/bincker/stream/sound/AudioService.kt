package cn.bincker.stream.sound

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer

const val PORT = 8888

const val PACKAGE_SIZE: Int = 1200

const val PACK_TYPE_PING: Byte = 0x00
const val PACK_TYPE_PONG: Byte = 0x01


const val PACK_TYPE_ECDH_REQUEST: Byte =              0b00010000
const val PACK_TYPE_ECDH_RESPONSE: Byte =             0b00010001
const val PACK_TYPE_PAIR_REQUEST: Byte =              0b00010010
const val PACK_TYPE_PAIR_RESPONSE: Byte =             0b00010011


const val PACK_TYPE_AUDIO_START: Byte =   0b00100000
const val PACK_TYPE_AUDIO_INFO: Byte =    0b00100001
const val PACK_TYPE_AUDIO_STOP: Byte =    0b00100010
const val PACK_TYPE_AUDIO_DATA: Byte =    0b00100100


const val PACK_TYPE_ENCRYPTED_DATA: Byte =    0b01000000
const val PACK_TYPE_SIGN_DATA: Byte =    0b01000001


class AudioService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var playJob: Job? = null
    private var play = true
    private var receiveMessageJob: Job? = null

    inner class AudioServiceBinder: Binder(){
        fun isPlay() = play
        suspend fun scan(){
            val job = sendMessage("255.255.255.255", ByteArray(1) {1})
            while (job.isActive) {
                delay(1000)
            }
        }
    }

    override fun onBind(intent: Intent) = AudioServiceBinder()

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
        if (!play){
            stop()
            play = true
            receiveMessage()
        }
        /*playJob = scope.launch {
            suspendCancellableCoroutine { coroutine ->
                var audioTrack: AudioTrack? = null
                var channel: SocketChannel? = null
                coroutine.invokeOnCancellation {
                    Log.d("AudioService.start", "do cancel")
                    channel?.close()
                    Log.d("AudioService.start", "do cancel channel completed")
                    audioTrack?.stop()
                    Log.d("AudioService.start", "do cancel audio completed")
                }
                SocketChannel.open(InetSocketAddress("192.168.2.4", 8888)).use { lChannel ->
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
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .build()
                    val lAudioTrack = AudioTrack.Builder()
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .setAudioAttributes(audioAttributes)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                            .setEncoding(encoding)
                            .setChannelMask(channelConfig)
                            .build())
                        .setBufferSizeInBytes(4096)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
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
                    lAudioTrack.play()
                    byteBuffer = ByteBuffer.allocate(lAudioTrack.bufferSizeInFrames * 2)
                    var zeroStart = 0L
                    var writeAudio = true
                    var allZero = true
                    try {
                        while (play && lChannel.read(byteBuffer) != -1) {
                            byteBuffer.flip()
                            if (byteBuffer.hasRemaining()) {
                                // 输出静音数据超过60秒会被系统强制静音, 所以检测全静音超过五秒则不再输出，直到有音频输入
                                for (i in byteBuffer.position() until byteBuffer.limit()){
                                    if (byteBuffer.array()[i].toInt() != 0) {
                                        allZero = false
                                        break
                                    }
                                }
                                if (allZero){
                                    if (zeroStart == 0L) {
                                        zeroStart = System.currentTimeMillis()
                                    }else if(writeAudio && System.currentTimeMillis() - zeroStart > 5000){
                                        byteBuffer.clear()
                                        writeAudio = false
                                    }
                                }else{
                                    if (zeroStart != 0L){
                                        zeroStart = 0
                                        writeAudio = false
                                    }
                                }
                                if (writeAudio) {
                                    lAudioTrack.write(byteBuffer, byteBuffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                                    byteBuffer.compact()
                                }else{
                                    byteBuffer.clear()
                                }
                            }
                        }
                    }catch (e: Exception){
                        if (play){
                            Log.d("AudioService.start", "receive stream error", e)
                        }
                    }
                }
            }
        }*/
    }

    private fun sendMessage(host: String, data: ByteArray) = scope.launch {
        DatagramSocket().use {
            if (host == "255.255.255.255"){
                it.broadcast = true
            }
            it.send(DatagramPacket(data, data.size, InetSocketAddress(host, PORT)))
        }
    }

    private fun receiveMessage(){
        receiveMessageJob = scope.launch {
            val data = ByteArray(PACKAGE_SIZE)
            val buffer = ByteBuffer.wrap(data)
            val datagramPacket = DatagramPacket(data, buffer.capacity())
            while (play) {
                val socket = DatagramSocket(PORT)
                socket.use {
                    while (play){
                        try {
                            socket.receive(datagramPacket)
                            buffer.clear()
                            Log.d("AudioService.receiveMessage", "receiveMessage: " + data.joinToString { "%02x".format(it) })
                        }catch (e: Exception){
                            Log.w("AudioService.receiveMessage", "receiveMessage: receive message error", e)
                        }
                    }
                }
            }
        }
    }

    private fun stop(){
        play = false
    }
}