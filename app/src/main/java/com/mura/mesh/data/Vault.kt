package com.mura.mesh.data

import android.content.Context
import com.mura.mesh.core.Crypto
import com.mura.mesh.identity.KeystoreWrap

/**
 * Encryption at rest for everything in the database.
 *
 * A random 256-bit data key encrypts every sensitive field (messages, names, file keys,
 * photos, group keys…). The data key is stored only wrapped by a non-exportable Android
 * Keystore key, so copying the app's files off the phone — even with root — yields
 * nothing readable. Media files are sealed separately with per-file keys that themselves
 * live encrypted in the database.
 */
object Vault {
    private const val PREFS = "vault"
    private const val ALIAS = "mura_vault_wrap"
    private val AAD = "mura-field-v1".toByteArray()

    @Volatile private var key: ByteArray? = null

    val dataKey: ByteArray get() = key ?: error("vault locked")
    val isOpen: Boolean get() = key != null

    @Synchronized
    fun open(ctx: Context) {
        if (key != null) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wrapped = p.getString("dk", null)
        key = if (wrapped != null) KeystoreWrap.unwrap(ALIAS, wrapped) else {
            val dk = Crypto.randomBytes(32)
            p.edit().putString("dk", KeystoreWrap.wrap(ALIAS, dk)).commit()
            dk
        }
    }

    /** Used when restoring a backup: adopt the data key the backup was written with. */
    @Synchronized
    fun install(ctx: Context, dk: ByteArray) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("dk", KeystoreWrap.wrap(ALIAS, dk)).commit()
        key = dk.copyOf()
    }

    fun enc(plain: ByteArray?): ByteArray? = plain?.let { Crypto.seal(dataKey, it, AAD) }
    fun dec(cipher: ByteArray?): ByteArray? = cipher?.let { try { Crypto.unseal(dataKey, it, AAD) } catch (e: Exception) { null } }
    fun encS(s: String?): ByteArray? = s?.let { enc(it.toByteArray(Charsets.UTF_8)) }
    fun decS(c: ByteArray?): String = dec(c)?.toString(Charsets.UTF_8) ?: ""
}
