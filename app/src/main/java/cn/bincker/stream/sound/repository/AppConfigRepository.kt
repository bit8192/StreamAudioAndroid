package cn.bincker.stream.sound.repository

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import cn.bincker.stream.sound.config.AppConfig
import cn.bincker.stream.sound.config.DeviceConfig
import cn.bincker.stream.sound.utils.generateEd25519AsBase64
import cn.bincker.stream.sound.utils.generateX25519KeyPair
import cn.bincker.stream.sound.utils.loadPrivateEd25519
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
    private lateinit var appConfig: AppConfig
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

    suspend fun refresh() {
        refreshAndGetConfig()
    }

    suspend fun refreshAndGetConfig() = mutex.withLock { loadAppConfig().also {
        appConfig = it
        if (it.privateKey.isNotBlank()){
            _privateKey = loadPrivateEd25519(it.privateKey)
        }else{
            it.privateKey = generateEd25519AsBase64()
            saveAppConfig(it)
        }
        _publicKey = _privateKey.generatePublicKey()
        _deviceConfigList.clear()
        _deviceConfigList.addAll(appConfig.devices)
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
        appConfig.let {
            it.devices = deviceConfigList
            saveAppConfig(it)
        }
    }

    suspend fun addDeviceConfig(config: DeviceConfig) {
        _deviceConfigList.add(config)
        saveAppConfig()
    }

    suspend fun updateDeviceConfig(oldAddress: String, newConfig: DeviceConfig) {
        mutex.withLock {
            val idx = _deviceConfigList.indexOfFirst { it.address == oldAddress }
            if (idx >= 0) {
                _deviceConfigList[idx] = newConfig
            } else {
                _deviceConfigList.add(newConfig)
            }
            saveAppConfig()
        }
    }

    suspend fun getAppConfigSnapshot(): AppConfig = mutex.withLock {
        appConfig.copy(devices = deviceConfigList.toList())
    }
}
