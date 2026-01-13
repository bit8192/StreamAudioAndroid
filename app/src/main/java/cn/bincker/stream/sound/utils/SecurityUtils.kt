package cn.bincker.stream.sound.utils

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun generateEd25519KeyPair(): AsymmetricCipherKeyPair {
    val keyPairGenerator = Ed25519KeyPairGenerator()
    keyPairGenerator.init(KeyGenerationParameters(SecureRandom(), 256))
    return keyPairGenerator.generateKeyPair()
}

fun generateEd25519AsBase64(): String = generateX25519KeyPair().let {
    Base64.getEncoder().encodeToString((it.private as Ed25519PrivateKeyParameters).encoded)
}

fun loadPrivateEd25519(key: String) = Ed25519PrivateKeyParameters(Base64.getDecoder().decode(key))

fun loadPublicEd25519(key: String) = Ed25519PublicKeyParameters(Base64.getDecoder().decode(key))

fun generateX25519KeyPair(): AsymmetricCipherKeyPair = X25519KeyPairGenerator().let {
    it.init(KeyGenerationParameters(SecureRandom(), 256))
    it.generateKeyPair()
}

/**
 * 插入从position 0到当前位置的签名数据，并移动position
 */
fun ByteBuffer.putSign(key: Ed25519PrivateKeyParameters): ByteBuffer{
    val sign = ByteArray(Ed25519.SIGNATURE_SIZE)
    key.sign(Ed25519.Algorithm.Ed25519, null, array(), 0, position(), sign, 0)
    put(sign)
    return this
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