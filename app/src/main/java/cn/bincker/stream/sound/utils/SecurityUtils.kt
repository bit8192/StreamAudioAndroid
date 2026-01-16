package cn.bincker.stream.sound.utils

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.KeyGenerationParameters
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun generateEd25519KeyPair(): AsymmetricCipherKeyPair {
    val keyPairGenerator = Ed25519KeyPairGenerator()
    keyPairGenerator.init(KeyGenerationParameters(SecureRandom(), 256))
    return keyPairGenerator.generateKeyPair()
}

fun generateEd25519AsBase64(): String = generateEd25519KeyPair().let {
    Base64.getEncoder().encodeToString((it.private as Ed25519PrivateKeyParameters).encoded)
}

fun loadPrivateEd25519(key: String) = Ed25519PrivateKeyParameters(Base64.getDecoder().decode(key))

fun loadPublicEd25519(key: String) = loadPublicEd25519(Base64.getDecoder().decode(key))

fun loadPublicEd25519(key: ByteArray) = Ed25519PublicKeyParameters(key)

fun loadPublicX25519(key: ByteArray) = X25519PublicKeyParameters(key, 0)

fun generateX25519KeyPair(): AsymmetricCipherKeyPair = X25519KeyPairGenerator().let {
    it.init(KeyGenerationParameters(SecureRandom(), 256))
    it.generateKeyPair()
}

fun ByteBuffer.verifySign(key: Ed25519PublicKeyParameters) = key.verify(
    Ed25519.Algorithm.Ed25519,
    null,
    array(),
    arrayOffset(),
    position(),
    array(),
    arrayOffset() + position()
)

/**
 * 校验sign，并移动position
 */
@OptIn(ExperimentalStdlibApi::class)
fun ByteBuffer.verifyAndGetSign(key: Ed25519PublicKeyParameters): ByteArray{
    if(!verifySign(key)){
        throw Exception("verify sign fail: hex=${this.array().toHexString()}\tpos=${position()}}")
    }
    return array().copyOfRange(arrayOffset() + position(), arrayOffset() + position() + Ed25519.SIGNATURE_SIZE)
}

/**
 * 计算从position 0到当前位置的签名数据，并不移动position
 */
fun ByteBuffer.computeSign(key: Ed25519PrivateKeyParameters): ByteArray{
    val sign = ByteArray(Ed25519.SIGNATURE_SIZE)
    key.sign(Ed25519.Algorithm.Ed25519, null, array(), 0, position(), sign, 0)
    return sign
}

/**
 * 插入从position 0到当前位置的签名数据，并移动position
 */
fun ByteBuffer.putSign(key: Ed25519PrivateKeyParameters): ByteBuffer{
    put(computeSign(key))
    return this
}

/**
 * hmac-sha256
 */
fun hMacSha256(
    key: ByteArray,
    content: ByteArray
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    val keySpec = SecretKeySpec(key, "HmacSHA256")
    mac.init(keySpec)

    // 添加盐值（如果有）
    if (content.isNotEmpty()) {
        mac.update(content)
    }

    // 返回派生的密钥
    return mac.doFinal()
}

/**
 * 计算 SHA-256 哈希值
 */
fun ByteArray.sha256(): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(this)
}

/**
 * 使用 AES-256-GCM 加密数据
 */
fun aes256gcmEncrypt(plainText: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = SecretKeySpec(key, "AES")
    val gcmSpec = GCMParameterSpec(128, iv)
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
    return cipher.doFinal(plainText)
}

/**
 * 使用 AES-256-GCM 解密数据
 */
fun aes256gcmDecrypt(cipherText: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val keySpec = SecretKeySpec(key, "AES")
    val gcmSpec = GCMParameterSpec(128, iv)
    cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
    return cipher.doFinal(cipherText)
}