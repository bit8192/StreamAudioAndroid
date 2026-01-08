package cn.bincker.stream.sound.repository

import android.content.Context
import android.util.Log
import cn.bincker.stream.sound.config.AppConfig
import cn.bincker.stream.sound.utils.generatePrivateKey
import cn.bincker.stream.sound.utils.generateX25519KeyPair
import cn.bincker.stream.sound.utils.loadPrivateKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
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
    private val edchKeyPair: AsymmetricCipherKeyPair = generateX25519KeyPair()
    private lateinit var _privateKey: StateFlow<Ed25519PrivateKeyParameters>
    private val privateKey: StateFlow<Ed25519PrivateKeyParameters> = _privateKey

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
        _privateKey = appConfig.filterNotNull()
            .map { it.privateKey }
            .filter { it.isNotBlank() }
            .map { loadPrivateKey(it) }
            .stateIn(CoroutineScope(Dispatchers.Default))
    }

    suspend fun getConfig() = _appConfig.value ?: mutex.withLock {
        _appConfig.value ?: loadAppConfig().also { _appConfig.value = it }
    }

    suspend fun refresh() {
        refreshAndGetConfig()
    }

    suspend fun refreshAndGetConfig() = mutex.withLock { loadAppConfig().also { _appConfig.value = it } }

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
            it.privateKey = generatePrivateKey()
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
}