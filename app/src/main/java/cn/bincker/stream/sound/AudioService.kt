package cn.bincker.stream.sound

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.util.Log
import android.widget.Toast
import androidx.core.content.getSystemService
import cn.bincker.stream.sound.config.DeviceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.StandardProtocolFamily
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.security.KeyPair
import java.security.KeyPairGenerator

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

val BROADCAST_ADDRESS: InetAddress = InetAddress.getByName("255.255.255.255")

private const val TAG = "AudioService"

class AudioService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var playJob: Job? = null
    private var play = false
    private var receiveMessageJob: Job? = null
    private var datagramChannel: DatagramChannel? = null
    private val localAddressList = mutableListOf<InetAddress>()
    private var scanning = false
    private var scanJob: Job? = null
    private var scanCallback: ((DeviceConfig)->Unit)? = null

    inner class AudioServiceBinder: Binder(){
        fun isPlay() = play
        fun getDeviceList(): List<DeviceConfig> {
            return emptyList()
        }
        fun pairDevice(uri: String){
            Log.d("AudioService.pairDevice", "pairDevice: uri=$uri")
            if (!uri.startsWith("streamsound://")){
                Log.w("AudioService.pairDevice", "pairDevice: unsupported uri $uri")
                Toast.makeText(this@AudioService, "Unsupported URI", Toast.LENGTH_LONG).show()
                return
            }

            val addressPart = uri.removePrefix("streamsound://").split(":")
            if (addressPart.size == 2){
                val host = addressPart[0]
                val port = addressPart[1].toIntOrNull() ?: PORT
                val address = InetSocketAddress(host, port)
                Log.d("AudioService.pairDevice", "pairDevice: address=$address")
            }
        }

        fun startScan(callback: (DeviceConfig)->Unit){
            if (scanning) return
            scanCallback = callback
            scanning = true
            scanJob = scope.launch {
                while (scanning) {
                    sendMessage(BROADCAST_ADDRESS, ByteArray(1))
                    delay(1000L)
                }
            }
        }
        fun stopScan(){
            scanning = false
            scanCallback = null
            scanJob?.cancel()
            scanJob = null
        }
    }

    override fun onBind(intent: Intent) = AudioServiceBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationId = "stream_audio_notification_channel"
        val channel = NotificationChannel(notificationId, "stream audio notification channel", NotificationManager.IMPORTANCE_LOW)
        getSystemService<NotificationManager>()!!.createNotificationChannel(channel)
        startForeground(1, Notification.Builder(this, notificationId).setContentTitle(getString(R.string.app_name)).setContentText("playing...").setSmallIcon(R.drawable.ic_launcher_foreground).build())
        val cmd = intent?.getIntExtra("cmd", -1)
        if (cmd == 0){
            start()
        }else if(cmd == 1){
            playJob?.cancel()
            playJob = null
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun start(){
        Log.d("AudioService.start", "start: play=$play")
        if (play) return
        stop()
        datagramChannel = DatagramChannel.open(StandardProtocolFamily.INET6).also {
            it.socket().broadcast = true
            Log.d("AudioService.start", "start: addr=${it.localAddress}")
        }
        startReceiveMessage()
        play = true
        localAddressList.clear()
        for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
            if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) continue
            localAddressList.addAll(networkInterface.inetAddresses.toList())
            if (networkInterface.name.contains("wlan")){
                datagramChannel?.setOption(StandardSocketOptions.IP_MULTICAST_IF, networkInterface)
            }
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

    private suspend fun sendMessage(address: InetAddress, data: ByteArray) {
        withContext(Dispatchers.IO) {
            datagramChannel?.send(ByteBuffer.wrap(data), InetSocketAddress(address, PORT))
        }
    }

    private fun startReceiveMessage(){
        Log.d("AudioService.startReceiveMessage", "start receive message")
        receiveMessageJob = scope.launch {
            val buffer = ByteBuffer.allocate(PACKAGE_SIZE)
            datagramChannel?.use { channel->
                while (play){
                    try {
                        buffer.clear()
                        val address: SocketAddress = channel.receive(buffer) ?: continue
                        if (address is InetSocketAddress){
                            if (localAddressList.any { it.address.contentEquals(address.address.address) }) continue
                        }

                        buffer.flip()
                        if (buffer.remaining() < 1) continue

                        Log.d(
                            "AudioService.receiveMessage",
                            "receiveMessage: ${buffer.array().copyOf(buffer.remaining()).joinToString("") { "%02X".format(it) }}"
                        )
                        when(buffer.get()){
                            PACK_TYPE_PONG -> {
//                                scanCallback?.invoke(DeviceConfig(if (address is InetSocketAddress) address.hostName else address.toString(), address))
                            }
                        }
                    }catch (e: Exception){
                        Log.w("AudioService.receiveMessage", "receiveMessage: receive message error", e)
                    }
                }
            }
        }
    }

    private fun stop(){
        play = false
    }
}