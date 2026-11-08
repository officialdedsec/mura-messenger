package com.mura.mesh.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.mura.mesh.MuraApp

/** Restarts the mesh after a reboot or app update, so messages keep flowing without opening the app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            MeshService.start(context)
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val DECLINE = "com.mura.mesh.DECLINE"
        const val HANGUP = "com.mura.mesh.HANGUP"
        const val REPLY = "com.mura.mesh.REPLY"
        const val MARK_READ = "com.mura.mesh.MARK_READ"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val engine = MuraApp.engine ?: return
        when (intent.action) {
            DECLINE -> engine.calls.decline()
            HANGUP -> engine.calls.hangup()
            REPLY -> {
                val chat = intent.getStringExtra(Notifier.EXTRA_CHAT) ?: return
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString() ?: return
                val pending = goAsync()
                MeshService.start(context)
                engine.replyFromNotification(chat, text).invokeOnCompletion { pending.finish() }
            }
            MARK_READ -> {
                val chat = intent.getStringExtra(Notifier.EXTRA_CHAT) ?: return
                engine.markChatRead(chat)
            }
        }
    }
}
