package ru.sixthcup.evotor.net

import android.util.Base64
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.SecureRandom
import java.security.Security

/**
 * Ed25519 совместим с @noble/curves на backend (publicKey base64url, 43 символа).
 */
object DeviceKeys {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    data class Pair(val publicKeyB64u: String, val privateKeyB64u: String)

    fun generate(): Pair {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        val priv = (kp.private as Ed25519PrivateKeyParameters).encoded
        val pub = (kp.public as Ed25519PublicKeyParameters).encoded
        return Pair(b64u(pub), b64u(priv))
    }

    fun sign(message: ByteArray, privateKeyB64u: String): ByteArray {
        val priv = Ed25519PrivateKeyParameters(b64uDecode(privateKeyB64u), 0)
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(message: ByteArray, signature: ByteArray, publicKeyB64u: String): Boolean {
        return try {
            val pub = Ed25519PublicKeyParameters(b64uDecode(publicKeyB64u), 0)
            val signer = Ed25519Signer()
            signer.init(false, pub)
            signer.update(message, 0, message.size)
            signer.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    fun b64u(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun b64uDecode(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        when (t.length % 4) {
            2 -> t += "=="
            3 -> t += "="
        }
        return Base64.decode(t, Base64.DEFAULT)
    }
}
