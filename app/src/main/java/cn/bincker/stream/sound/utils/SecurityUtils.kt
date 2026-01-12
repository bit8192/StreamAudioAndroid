package cn.bincker.stream.sound.utils

import android.util.Base64
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec


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

/**
 * 使用 HMAC-SHA256 派生密钥 (Key Derivation Function)
 * 与 C++ 端的 hmac_derive_key 实现保持一致
 *
 * @param sharedSecret X25519 ECDH 共享密钥
 * @param salt 盐值 (可为空)
 * @param info 上下文信息 (默认为 "steam-audi0")
 * @return 派生的 32 字节密钥
 */
fun hmacDeriveKey(
    sharedSecret: ByteArray,
    salt: ByteArray = ByteArray(0),
    info: ByteArray = byteArrayOf('s'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(),
                                    'a'.code.toByte(), 'm'.code.toByte(), '-'.code.toByte(),
                                    'a'.code.toByte(), 'u'.code.toByte(), 'd'.code.toByte(),
                                    'i'.code.toByte(), '0'.code.toByte())
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    val keySpec = SecretKeySpec(sharedSecret, "HmacSHA256")
    mac.init(keySpec)

    // 添加盐值（如果有）
    if (salt.isNotEmpty()) {
        mac.update(salt)
    }

    // 添加上下文信息
    if (info.isNotEmpty()) {
        mac.update(info)
    }

    // 返回派生的密钥
    return mac.doFinal()
}