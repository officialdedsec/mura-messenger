package com.mura.mesh.identity

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.X25519
import com.mura.mesh.core.Crypto
import com.mura.mesh.core.Curve25519
import com.mura.mesh.core.Ed25519
import com.mura.mesh.core.toHex
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** X25519 from Google Tink: pure Java, works on Android 9 and newer without Play Services. */
object TinkCurve : Curve25519 {
    override fun generatePrivate(): ByteArray = X25519.generatePrivateKey()
    override fun publicFrom(privateKey: ByteArray): ByteArray = X25519.publicFromPrivate(privateKey)
    override fun shared(privateKey: ByteArray, peerPublic: ByteArray): ByteArray = X25519.computeSharedSecret(privateKey, peerPublic)
}

object TinkEd : Ed25519 {
    override fun publicFromSeed(seed: ByteArray): ByteArray = Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey
    override fun sign(seed: ByteArray, message: ByteArray): ByteArray = Ed25519Sign(seed).sign(message)
    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        try { Ed25519Verify(publicKey).verify(signature, message); true } catch (e: GeneralSecurityException) { false }
}

class Identity(val privateKey: ByteArray, val publicKey: ByteArray, var name: String) {
    val nodeId: ByteArray = Crypto.nodeId(publicKey)
    val publicHex: String = publicKey.toHex()
    val edSeed: ByteArray = Crypto.edSeedFrom(privateKey)
    val edPub: ByteArray = TinkEd.publicFromSeed(edSeed)

    /** Fingerprint shown to the user, e.g. "7F2A 91C4 E08B 3D5E". */
    val fingerprint: String = Crypto.sha256(publicKey).copyOf(8).toHex().uppercase().chunked(4).joinToString(" ")
}

/**
 * Wrapping keys that live inside the Android Keystore (in the phone's secure hardware —
 * StrongBox or TEE — where available). They can be used by this app but can never be
 * exported, not even on a rooted phone, and are deleted with the app.
 */
object KeystoreWrap {
    private fun key(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            gen.init(spec(true)); gen.generateKey()
        } catch (e: StrongBoxUnavailableException) {
            gen.init(spec(false)); gen.generateKey()
        } catch (e: Exception) {
            gen.init(spec(false)); gen.generateKey()
        }
    }

    fun wrap(alias: String, plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(alias))
        return Base64.encodeToString(c.iv + c.doFinal(plain), Base64.NO_WRAP)
    }

    fun unwrap(alias: String, s: String): ByteArray {
        val all = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, all.copyOfRange(0, 12)))
        return c.doFinal(all.copyOfRange(12, all.size))
    }
}

/** The private key never leaves the phone except inside an encrypted backup the user creates. */
object IdentityStore {
    private const val PREFS = "identity"
    private const val ALIAS = "mura_identity_wrap"

    fun exists(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("key")

    fun load(ctx: Context): Identity? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wrapped = p.getString("key", null) ?: return null
        val priv = try { KeystoreWrap.unwrap(ALIAS, wrapped) } catch (e: Exception) { return null }
        return Identity(priv, TinkCurve.publicFrom(priv), p.getString("name", "") ?: "")
    }

    fun create(ctx: Context, name: String, restoredPrivate: ByteArray? = null): Identity {
        val priv = restoredPrivate ?: TinkCurve.generatePrivate()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("key", KeystoreWrap.wrap(ALIAS, priv))
            .putString("name", name.trim())
            .commit()
        return Identity(priv, TinkCurve.publicFrom(priv), name.trim())
    }

    fun rename(ctx: Context, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("name", name.trim()).apply()
    }

    // ---- backup code: lets the user move their identity to a new phone ----

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun backupCode(id: Identity): String {
        val data = id.privateKey + Crypto.sha256(id.privateKey).copyOf(2)
        val sb = StringBuilder()
        var buffer = 0; var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff); bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(buffer shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.chunked(5).joinToString("-")
    }

    fun parseBackupCode(code: String): ByteArray? {
        val clean = code.uppercase().filter { it in ALPHABET }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0; var bits = 0
        for (ch in clean) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(ch); bits += 5
            if (bits >= 8) { out.write((buffer shr (bits - 8)) and 0xff); bits -= 8 }
        }
        val data = out.toByteArray()
        if (data.size < 34) return null
        val priv = data.copyOfRange(0, 32)
        return if (Crypto.sha256(priv).copyOf(2).contentEquals(data.copyOfRange(32, 34))) priv else null
    }
}
