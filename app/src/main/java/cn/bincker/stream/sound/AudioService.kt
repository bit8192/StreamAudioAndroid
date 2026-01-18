package cn.bincker.stream.sound

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.util.Log
import androidx.core.content.getSystemService
import cn.bincker.stream.sound.entity.Device
import cn.bincker.stream.sound.entity.PairDevice
import cn.bincker.stream.sound.repository.AppConfigRepository
import cn.bincker.stream.sound.service.DeviceConnectionManager
import cn.bincker.stream.sound.vm.ConnectionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject


const val INTENT_EXTRA_KEY_CMD = "cmd"
const val INTENT_EXTRA_KEY_DEVICE_NAME = "device-name"
const val INTENT_EXTRA_KEY_PAIR_URI = "pair-uri"

enum class AudioServiceCommandEnum {
    PAIR,
    CONNECT,
    PLAY,
    STOP,
    DISCONNECT
}

private const val TAG = "AudioService"

@AndroidEntryPoint
class AudioService : Service() {
    @Inject
    lateinit var appConfigRepository: AppConfigRepository

    @Inject
    lateinit var deviceConnectionManager: DeviceConnectionManager

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class AudioServiceBinder: Binder() {
        // 获取连接管理器的状态流
        fun getConnectionStates(): StateFlow<Map<String, ConnectionState>> =
            deviceConnectionManager.connectionStates

        fun getPlayingStates(): StateFlow<Map<String, Boolean>> =
            deviceConnectionManager.playingStates

        fun getErrorMessages(): StateFlow<Map<String, String?>> =
            deviceConnectionManager.errorMessages

        // 获取设备列表
        fun getDeviceList(): List<Device> = deviceConnectionManager.deviceList

        // 刷新设备列表
        fun refreshDeviceList() {
            scope.launch {
                deviceConnectionManager.refreshDeviceList()
            }
        }

        // 连接设备
        fun connectDevice(device: Device) {
            scope.launch {
                deviceConnectionManager.connectDevice(device, scope)
            }
        }

        // 断开设备
        fun disconnectDevice(deviceId: String) {
            scope.launch {
                deviceConnectionManager.disconnectDevice(deviceId)
            }
        }

        // 切换播放状态
        fun togglePlayback(deviceId: String) {
            scope.launch {
                deviceConnectionManager.togglePlayback(deviceId, scope)
            }
        }

        // 清除错误
        fun clearError(deviceId: String) {
            deviceConnectionManager.clearError(deviceId)
        }

        // 配对设备
        fun pairDevice(uri: String) {
            this@AudioService.startService(Intent(this@AudioService, AudioService::class.java).apply {
                putExtra(INTENT_EXTRA_KEY_CMD, AudioServiceCommandEnum.PAIR.name)
                putExtra(INTENT_EXTRA_KEY_PAIR_URI, uri)
            })
        }
    }

    override fun onBind(intent: Intent) = AudioServiceBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificationId = "stream_audio_notification_channel"
        val channel = NotificationChannel(notificationId, "stream audio notification channel", NotificationManager.IMPORTANCE_LOW)
        getSystemService<NotificationManager>()!!.createNotificationChannel(channel)
        startForeground(1, Notification.Builder(this, notificationId).setContentTitle(getString(R.string.app_name)).setContentText("playing...").setSmallIcon(R.drawable.ic_launcher_foreground).build())

        val cmd = intent?.getStringExtra(INTENT_EXTRA_KEY_CMD)?.let {
            try {
                AudioServiceCommandEnum.valueOf(it)
            }catch (e: Exception) {
                Log.e(TAG, "onStartCommand: unknown cmd, cmd=${it}", e)
            }
        } ?: return super.onStartCommand(intent, flags, startId)
        scope.launch {
            try {
                when (cmd) {
                    AudioServiceCommandEnum.PAIR -> {
                        val uri = intent.getStringExtra(INTENT_EXTRA_KEY_PAIR_URI) ?: throw Exception("bad pair command, not found pair uri extra")
                        pair(uri)
                    }
                    AudioServiceCommandEnum.CONNECT -> {
                        val deviceName = intent.getStringExtra(INTENT_EXTRA_KEY_DEVICE_NAME) ?: throw Exception("bad connect command, not found device name extra")
                        connect(deviceName)
                    }
                }
            }catch (e: Exception){
                Log.e(TAG, "onStartCommand: execute action error", e)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private suspend fun pair(uri: String){
        withContext(Dispatchers.IO) {
            Log.d(TAG, "pair: $uri")
            val pairDevice = PairDevice.parseUri(uri)
            val device = Device(deviceConnectionManager,pairDevice.device)
            var listenerJob: Job? = null
            try {
                device.connect()
                listenerJob = device.startListening(scope)
                device.pair(pairDevice.pairCode)
                // 配对成功后，将设备添加到DeviceConnectionManager
                deviceConnectionManager.addDevice(device, listenerJob)
            }catch (e: Exception){
                listenerJob?.cancel()
                device.disconnect()
                throw e
            }
        }
    }

    private suspend fun connect(deviceName: String){
        withContext(Dispatchers.IO) {
            val device = deviceConnectionManager.deviceList.find { it.config.name == deviceName }
                ?: throw Exception("device [$deviceName] not found")
            device.connect()
            device.startListening(scope)
            device.ecdh()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.launch {
            deviceConnectionManager.cleanup()
        }
        scope.cancel()
    }

    private fun start(){
        /*
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
        playJob = scope.launch {
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

}