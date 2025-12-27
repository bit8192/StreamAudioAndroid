package cn.bincker.stream.sound.utils

import android.content.Context
import android.util.Base64
import android.util.Log
import cn.bincker.stream.sound.config.AppConfig
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import org.yaml.snakeyaml.inspector.TagInspector
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.io.path.Path

private const val CONFIG_FILE_PATH = "app_config.yaml"
private const val TAG = "AppConfigUtils"

fun getConfigFilePath(context: Context): Path {
    return Path(context.filesDir.path,CONFIG_FILE_PATH)
}

fun buildYaml(): Yaml{
    return Yaml(Constructor(AppConfig::class.java, LoaderOptions().apply {
        tagInspector = TagInspector {
            it.className == AppConfig::class.java.name
        }
    }))
}

fun loadAppConfig(context: Context): AppConfig {
    val configFilePath = getConfigFilePath(context)
    if (Files.notExists(configFilePath)) {
        return newAppConfig(context)
    }
    FileInputStream(configFilePath.toFile()).use { inputStream ->
        try {
            return buildYaml().load(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "loadAppConfig: ", e)
            return AppConfig()
        }
    }
}

fun newAppConfig(context: Context): AppConfig {
    return AppConfig().also {
        it.privateKey = generatePrivateKey()
        saveAppConfig(context, it)
    }
}

fun saveAppConfig(context: Context, config: AppConfig) {
    val configFilePath = getConfigFilePath(context)
    OutputStreamWriter(FileOutputStream(configFilePath.toFile())).use { writer ->
        try {
            buildYaml().dump(config, writer)
        } catch (e: Exception) {
            Log.e(TAG, "saveAppConfig: ", e)
        }
    }
}
