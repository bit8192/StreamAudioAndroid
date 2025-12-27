package cn.bincker.stream.sound

import android.app.Application
import cn.bincker.stream.sound.config.AppConfig
import cn.bincker.stream.sound.utils.generateX25519KeyPair
import cn.bincker.stream.sound.utils.loadAppConfig
import cn.bincker.stream.sound.utils.loadPrivateKey
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class Application: Application() {
    private lateinit var config: AppConfig
    private lateinit var edchKeyPair: AsymmetricCipherKeyPair
    private lateinit var privateKey: Ed25519PrivateKeyParameters

    init {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.addProvider(BouncyCastleProvider())
    }

    override fun onCreate() {
        super.onCreate()
        refreshAppConfig()
        edchKeyPair = generateX25519KeyPair()
        privateKey = loadPrivateKey(config.privateKey)
    }

    fun getAppConfig(): AppConfig = config

    fun refreshAppConfig() {
        config = loadAppConfig(this)
    }

    fun getEdchKeyPair(): AsymmetricCipherKeyPair = edchKeyPair
}