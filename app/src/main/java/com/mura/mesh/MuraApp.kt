package com.mura.mesh

import android.app.Application
import com.mura.mesh.backup.BackupScheduler
import com.mura.mesh.core.Crypto
import com.mura.mesh.data.Db
import com.mura.mesh.data.Vault
import com.mura.mesh.identity.Identity
import com.mura.mesh.identity.IdentityStore
import com.mura.mesh.identity.TinkCurve
import com.mura.mesh.identity.TinkEd
import com.mura.mesh.media.SecureMedia
import com.mura.mesh.mesh.MeshEngine
import com.mura.mesh.service.Notifier

class MuraApp : Application() {

    companion object {
        lateinit var instance: MuraApp
            private set

        /** The running messenger, or null before the user has created an identity. */
        val engine: MeshEngine? get() = instance.engineRef
    }

    val db: Db by lazy { Vault.open(this); Db(this) }

    @Volatile private var engineRef: MeshEngine? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Crypto.curve = TinkCurve
        Crypto.ed = TinkEd
        Vault.open(this)
        Notifier.createChannels(this)
        com.mura.mesh.ui.Disguise.load(this)
        SecureMedia.wipeTemp(this)
        IdentityStore.load(this)?.let { initEngine(it); com.mura.mesh.service.KeepAlive.schedule(this) }
    }

    @Synchronized
    fun initEngine(identity: Identity): MeshEngine =
        engineRef ?: MeshEngine(this, identity, db).also {
            engineRef = it
            BackupScheduler.ensureScheduled(this)
        }

    /**
     * Panic erase: wipes every chat, file, key and setting, then the app starts as new.
     * Deleting the Android Keystore keys makes anything left in flash storage unreadable.
     */
    @Synchronized
    fun panicErase() {
        val e = engineRef
        engineRef = null
        try { e?.calls?.hangup() } catch (_: Exception) {}
        try { e?.transport?.shutdown() } catch (_: Exception) {}
        try { e?.online?.shutdown() } catch (_: Exception) {}
        com.mura.mesh.ui.Disguise.reset(this)          // normal icon back, before its settings are wiped
        try { stopService(android.content.Intent(this, com.mura.mesh.service.MeshService::class.java)) } catch (_: Exception) {}
        try { androidx.work.WorkManager.getInstance(this).cancelAllWork() } catch (_: Exception) {}
        try { androidx.core.app.NotificationManagerCompat.from(this).cancelAll() } catch (_: Exception) {}
        try { db.close() } catch (_: Exception) {}
        deleteDatabase("mura2.db")
        filesDir.deleteRecursively()
        cacheDir.deleteRecursively()
        noBackupFilesDir.deleteRecursively()                                   // translation packs
        // Tor's state, including its directory cache (getDir() folders live outside filesDir).
        java.io.File(applicationInfo.dataDir).listFiles { f -> f.isDirectory && f.name.startsWith("app_") }?.forEach { it.deleteRecursively() }
        java.io.File(applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { f ->
            getSharedPreferences(f.name.removeSuffix(".xml"), MODE_PRIVATE).edit().clear().commit()
            f.delete()
        }
        try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (alias in ks.aliases().toList()) ks.deleteEntry(alias)
        } catch (_: Exception) {}
    }

    /** After restoring a backup the database file was replaced: start fresh objects. */
    @Synchronized
    fun resetForRestore() {
        engineRef?.stop()
        engineRef = null
    }
}
