package cn.bincker.stream.sound.repository

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import cn.bincker.stream.sound.config.AppConfig
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.entity.Device
import cn.bincker.stream.sound.utils.generateEd25519AsBase64
import cn.bincker.stream.sound.utils.generateX25519KeyPair
import cn.bincker.stream.sound.utils.loadPrivateEd25519
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import org.yaml.snakeyaml.inspector.TagInspector
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.security.Security
import kotlin.io.path.Path

private const val CONFIG_FILE_PATH = "app_config.yaml"
private const val TAG = "AppConfigUtils"

private val IO_DISPATCHER: CoroutineDispatcher = Dispatchers.IO

private val YAML: Yaml = Yaml(Constructor(AppConfig::class.java, LoaderOptions().apply {
    tagInspector = TagInspector {
        it.className == AppConfig::class.java.name
    }
}))

class AppConfigRepository {
    private val context: Context
    private var _appConfig = MutableStateFlow<AppConfig?>(null)
    val appConfig = _appConfig.stateIn(
        CoroutineScope(Dispatchers.Default),
        SharingStarted.WhileSubscribed(5000),
        AppConfig()
    )
    private val mutex = Mutex()
    private constructor(context: Context){
        this.context = context
    }
    val ecdhKeyPair: AsymmetricCipherKeyPair = generateX25519KeyPair()
    private lateinit var _privateKey: Ed25519PrivateKeyParameters
    val privateKey get() = _privateKey
    private lateinit var _publicKey: Ed25519PublicKeyParameters
    val publicKey get() = _publicKey
    //配置文件中的设备列表
    private val _deviceConfigList = mutableStateListOf<DeviceConfig>()
    val deviceConfigList: List<DeviceConfig> get() = _deviceConfigList
    //连接的设备列表
    private val _deviceList = mutableStateListOf<Device>()
    val deviceList: List<Device> get() = _deviceList

    companion object{
        init {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
        suspend fun newInstance(context: Context) = AppConfigRepository(context).also {
            it.init()
        }
    }

    suspend fun init() {
        refresh()
    }

    suspend fun getConfig() = _appConfig.value ?: refreshAndGetConfig()

    suspend fun refresh() {
        refreshAndGetConfig()
    }

    suspend fun refreshAndGetConfig() = mutex.withLock { loadAppConfig().also {
        _appConfig.value = it
        if (it.privateKey.isNotBlank()){
            _privateKey = loadPrivateEd25519(it.privateKey)
        }else{
            it.privateKey = generateEd25519AsBase64()
            saveAppConfig(it)
        }
        _publicKey = _privateKey.generatePublicKey()
        _deviceConfigList.clear()
        appConfig.value?.devices?.let { configs-> _deviceConfigList.addAll(configs) }
    }}

    fun getConfigFilePath() = Path(context.filesDir.path,CONFIG_FILE_PATH)

    suspend fun loadAppConfig(): AppConfig {
        val configFilePath = getConfigFilePath()
        if (Files.notExists(configFilePath)) {
            return newAppConfig()
        }
        return withContext(IO_DISPATCHER){
            FileInputStream(configFilePath.toFile()).use { inputStream ->
                try {
                    YAML.load(inputStream)
                } catch (e: Exception) {
                    Log.e(TAG, "loadAppConfig: ", e)
                    AppConfig()
                }
            }
        }
    }

    suspend fun newAppConfig(): AppConfig {
        return AppConfig().also {
            it.privateKey = generateEd25519AsBase64()
            saveAppConfig(it)
        }
    }

    suspend fun saveAppConfig(config: AppConfig) {
        val configFilePath = getConfigFilePath()
        withContext(IO_DISPATCHER) {
            OutputStreamWriter(FileOutputStream(configFilePath.toFile())).use { writer ->
                try {
                    YAML.dump(config, writer)
                } catch (e: Exception) {
                    Log.e(TAG, "saveAppConfig: ", e)
                }
            }
        }
    }

    suspend fun saveAppConfig() {
        _appConfig.value?.let {
            it.devices = deviceConfigList
            saveAppConfig(it)
        }
    }

    suspend fun addDeviceConfig(config: DeviceConfig) {
        _deviceConfigList.add(config)
        saveAppConfig()
    }
    fun addDevice(device: Device) = _deviceList.add(device)
}