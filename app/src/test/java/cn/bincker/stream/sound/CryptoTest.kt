package cn.bincker.stream.sound

import cn.bincker.stream.sound.utils.generateEd25519AsBase64
import cn.bincker.stream.sound.utils.generateEd25519KeyPair
import cn.bincker.stream.sound.utils.generateX25519KeyPair
import cn.bincker.stream.sound.utils.loadPrivateEd25519
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoTest {
    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun ecdh() {
        val keyPair1 = generateX25519KeyPair()
        val keyPair2 = generateX25519KeyPair()
        val publicKey1 = keyPair1.public as X25519PublicKeyParameters
        val privateKey1 = keyPair1.private as X25519PrivateKeyParameters
        val publicKey2 = keyPair2.public as X25519PublicKeyParameters
        val privateKey2 = keyPair2.private as X25519PrivateKeyParameters
        val secret1 = ByteArray(32)
        val secret2 = ByteArray(32)
        privateKey1.generateSecret(publicKey2, secret1, 0)
        privateKey2.generateSecret(publicKey1, secret2, 0)
        println("Secret 1: ${secret1.toHexString()}")
        println("Secret 2: ${secret2.toHexString()}")
        assert(secret1.contentEquals(secret2))
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun qrCodeLenTest() {
        val key = loadPrivateEd25519(generateEd25519AsBase64())
        val pubkey = key.generatePublicKey()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("test".toByteArray(), "HmacSHA256"))
        mac.update(pubkey.encoded)
        val byteBuffer = ByteBuffer.allocate(256)
        byteBuffer.put(mac.doFinal())
        byteBuffer.flip()
        for (i in 0 until ((byteBuffer.capacity().toDouble() / (Long.SIZE_BYTES * 8)).toInt())){
            print(byteBuffer.getLong().toULong().toString(36))
        }
        println()
        println(Base64.getEncoder().encodeToString(mac.doFinal()))
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun signTest() {
        val keyPair = generateEd25519KeyPair()
        val key = keyPair.private as Ed25519PrivateKeyParameters
        val pubkey = keyPair.public as Ed25519PublicKeyParameters
        val data = "test".toByteArray()
        val result = ByteArray(Ed25519.SIGNATURE_SIZE)
        key.sign(Ed25519.Algorithm.Ed25519, null, data, 0, data.size, result, 0)
        println("sign: ${result.toHexString()}\tlen=${result.size}\tlong len:${Long.SIZE_BYTES}")
        Assert.assertTrue(
            pubkey.verify(
                Ed25519.Algorithm.Ed25519,
                null,
                data,
                0,
                data.size,
                result,
                0
            )
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Test
    fun aes256test() {
        val encryptAes = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = "iv".toByteArray()
        val param = GCMParameterSpec(128, iv)
        val key = MessageDigest.getInstance("SHA256").digest("key".toByteArray())
        val data = "hello".toByteArray()
        println("raw value: " + String(data))
        println("raw data: " + data.toHexString())
        encryptAes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), param)
        val encryptedData = encryptAes.doFinal(data)
        println("encryptedData: " + encryptedData.toHexString())

        val decryptAes = Cipher.getInstance("AES/GCM/NoPadding")
        decryptAes.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), param)
        val decryptedData = decryptAes.doFinal(encryptedData)
        println("decryptedData: " + decryptedData.toHexString())
        println("decrypted value: " + String(decryptedData))
    }
}