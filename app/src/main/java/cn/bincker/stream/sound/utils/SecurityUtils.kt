package cn.bincker.stream.sound.utils

import android.util.Base64
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.security.SecureRandom


fun generatePrivateKey(): String {
    val keyPairGenerator = Ed25519KeyPairGenerator()
    keyPairGenerator.init(KeyGenerationParameters(SecureRandom(), 256))
    return keyPairGenerator.generateKeyPair().let {
        Base64.encodeToString((it.private as Ed25519PrivateKeyParameters).encoded, Base64.DEFAULT)
    }
}

fun loadPrivateKey(key: String) = Ed25519PrivateKeyParameters(Base64.decode(key, Base64.DEFAULT))

fun loadPublicKey(key: String) = Ed25519PublicKeyParameters(Base64.decode(key, Base64.DEFAULT))

fun generateX25519KeyPair(): AsymmetricCipherKeyPair = X25519KeyPairGenerator().let {
    it.init(KeyGenerationParameters(SecureRandom(), 256))
    it.generateKeyPair()
}