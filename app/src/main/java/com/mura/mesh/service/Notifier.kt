package com.mura.mesh.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.mura.mesh.R
import com.mura.mesh.mesh.MeshPermissions
import com.mura.mesh.ui.MainActivity

class Notifier(private val ctx: Context) {
    /** Calculator disguise on: neutral icon and wording, nothing that says "Mura". */
    private val disguised: Boolean get() = com.mura.mesh.ui.Disguise.enabledState
    private val icon: Int get() = if (disguised) R.drawable.ic_stat_generic else R.drawable.ic_stat_mura


    companion object {
        const val CH_MESH = "mesh"
        // v2 channels carry Mura's own sounds (a channel's sound can't change after it's created).
        const val CH_MESSAGES = "messages_v2"
        const val CH_CALLS = "calls_v2"
        const val CH_ALERTS = "alerts"
        const val KEY_REPLY = "mura_reply"
        const val ID_SERVICE = 1
        const val ID_CALL = 2
        const val ID_ONGOING_CALL = 3

        const val EXTRA_CHAT = "open_chat"
        const val EXTRA_CALL = "open_call"
        const val EXTRA_ANSWER = "answer_call"
        /** Secret stamped on our own notification intents, so another app can't open chats or answer calls. */
        const val EXTRA_TOKEN = "mura_token"

        @Volatile private var token: String? = null
        fun token(ctx: Context): String = token ?: synchronized(this) {
            token ?: run {
                val f = java.io.File(ctx.noBackupFilesDir, "intent.token")
                val t = f.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length == 32 }
                    ?: com.mura.mesh.core.Crypto.randomBytes(16).joinToString("") { "%02x".format(it) }.also { f.writeText(it) }
                t.also { token = it }
            }
        }
        /** True only for intents built by Mura itself (constant-time compare). */
        fun isOurs(ctx: Context, i: Intent): Boolean {
            val got = i.getStringExtra(EXTRA_TOKEN) ?: return false
            return java.security.MessageDigest.isEqual(got.toByteArray(), token(ctx).toByteArray())
        }

        private const val AMBER = 0xFFFFB547.toInt()

        fun soundUri(ctx: Context, res: Int): Uri = Uri.parse("android.resource://${ctx.packageName}/$res")

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.deleteNotificationChannel("messages")
            nm.deleteNotificationChannel("calls")
            val chime = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val mesh = NotificationChannel(CH_MESH, "Mesh connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps Mura reachable and relaying in the background"
                setShowBadge(false)
            }
            val messages = NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages, photos, voice notes and files"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 60, 90, 60)
                setSound(soundUri(ctx, R.raw.mura_ping), chime)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val calls = NotificationChannel(CH_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming voice calls"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 600, 800, 600)
                setSound(
                    soundUri(ctx, R.raw.mura_ring),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val alerts = NotificationChannel(CH_ALERTS, "Security alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Screenshots of your chats and other privacy alerts"
            }
            nm.createNotificationChannels(listOf(mesh, messages, calls, alerts))
        }
    }

    private val nm = NotificationManagerCompat.from(ctx)

    /** Lines shown in a chat's notification. sender == null means me (a reply sent from the notification). */
    private class Line(val sender: String?, val text: String, val at: Long)
    private class ChatThread(val title: String, val group: Boolean, val locked: Boolean, val canReply: Boolean, val lines: ArrayDeque<Line> = ArrayDeque())
    private val threads = HashMap<String, ChatThread>()
    private val me: Person by lazy { Person.Builder().setName("You").build() }

    private fun canPost() = MeshPermissions.notificationsGranted(ctx) && nm.areNotificationsEnabled()

    private fun openIntent(extraKey: String?, extra: String?, requestCode: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (extraKey != null) putExtra(extraKey, extra)
            putExtra(EXTRA_TOKEN, token(ctx))
        }
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun serviceNotification(text: String): Notification =
        NotificationCompat.Builder(ctx, CH_MESH)
            .setSmallIcon(icon)
            .setColor(AMBER)
            .setContentTitle(if (disguised) "Sync is on" else "Mura mesh is on")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent(null, null, 1))
            .build()

    @SuppressLint("MissingPermission")
    fun updateService(text: String) {
        if (!canPost()) return
        nm.notify(ID_SERVICE, serviceNotification(text))
    }

    /**
     * A new message, shown WhatsApp-style: the conversation with the latest lines, plus
     * "Reply" (type an answer right in the notification) and "Mark as read".
     * [sender] is the member name in groups (null in 1:1 chats).
     */
    @Synchronized
    @SuppressLint("MissingPermission")
    fun message(chat: String, rawTitle: String, sender: String?, rawText: String, locked: Boolean, canReply: Boolean) {
        if (!canPost()) return
        // Privacy setting: show everything, only who it's from, or nothing at all. Locked chats never show anything.
        val mode = if (locked) "none" else try {
            com.mura.mesh.MuraApp.instance.db.getSetting(com.mura.mesh.mesh.Settings.NOTIF_PREVIEW, "full")
        } catch (e: Exception) { "full" }
        val title = if (mode == "none") "Mura" else rawTitle
        val text = if (mode == "full") rawText else "New message"
        val who = if (mode == "full") sender else null
        val t = threads.getOrPut(chat) { ChatThread(title, sender != null, locked, canReply && !locked) }
        val thread = if (t.title != title || t.locked != locked) ChatThread(title, sender != null, locked, canReply && !locked, t.lines).also { threads[chat] = it } else t
        thread.lines.addLast(Line(who ?: title, text.take(300), System.currentTimeMillis()))
        while (thread.lines.size > 8) thread.lines.removeFirst()
        post(chat, thread, alert = true)
    }

    /** Adds what I typed in the notification, then keeps it quiet. */
    @Synchronized
    @SuppressLint("MissingPermission")
    fun addMyReply(chat: String, text: String) {
        val thread = threads[chat] ?: return
        thread.lines.addLast(Line(null, text.take(300), System.currentTimeMillis()))
        while (thread.lines.size > 8) thread.lines.removeFirst()
        post(chat, thread, alert = false)
    }

    @SuppressLint("MissingPermission")
    private fun post(chat: String, thread: ChatThread, alert: Boolean) {
        if (!canPost()) return
        val id = chat.hashCode()
        val style = NotificationCompat.MessagingStyle(me)
        if (thread.group) style.setConversationTitle(thread.title)
        style.setGroupConversation(thread.group)
        for (l in thread.lines) {
            val person = if (l.sender == null) null else Person.Builder().setName(l.sender).setKey(if (thread.group) l.sender else chat).build()
            style.addMessage(NotificationCompat.MessagingStyle.Message(l.text, l.at, person))
        }
        val publicVersion = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(icon).setColor(AMBER)
            .setContentTitle(if (disguised) "Notification" else "Mura").setContentText("New message").build()
        val b = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(icon)
            .setColor(AMBER)
            .setContentTitle(thread.title)
            .setContentText(thread.lines.lastOrNull()?.text ?: "")
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOnlyAlertOnce(!alert)
            .setAutoCancel(true)
            .setContentIntent(openIntent(EXTRA_CHAT, chat, id))
        if (!alert) b.setSilent(true)
        if (thread.canReply) {
            val replyIntent = PendingIntent.getBroadcast(ctx, id + 11,
                Intent(ctx, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.REPLY).putExtra(EXTRA_CHAT, chat),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            val input = RemoteInput.Builder(KEY_REPLY).setLabel("Sealed reply…").build()
            b.addAction(NotificationCompat.Action.Builder(icon, "Reply", replyIntent)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build())
        }
        val readIntent = PendingIntent.getBroadcast(ctx, id + 12,
            Intent(ctx, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.MARK_READ).putExtra(EXTRA_CHAT, chat),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        b.addAction(NotificationCompat.Action.Builder(icon, "Mark as read", readIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build())
        nm.notify(id, b.build())
    }

    @Synchronized
    fun cancelChat(chat: String) {
        threads.remove(chat)
        nm.cancel(chat.hashCode())
    }

    @SuppressLint("MissingPermission")
    fun alert(peerHex: String, name: String, text: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(icon)
            .setColor(AMBER)
            .setContentTitle(name)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent(EXTRA_CHAT, peerHex, peerHex.hashCode() + 7))
            .build()
        nm.notify(peerHex.hashCode() + 7, n)
    }

    @SuppressLint("MissingPermission")
    fun info(id: Int, title: String, text: String) {
        if (!canPost()) return
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(icon).setColor(AMBER)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openIntent(null, null, 1000 + id))
            .build()
        nm.notify(1000 + id, n)
    }

    @SuppressLint("MissingPermission")
    fun incomingCall(peerHex: String, name: String) {
        if (!canPost()) return
        val fullScreen = openIntent(EXTRA_CALL, peerHex, 20)
        val answer = PendingIntent.getActivity(ctx, 21,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_ANSWER, peerHex)
                putExtra(EXTRA_TOKEN, token(ctx))
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val decline = PendingIntent.getBroadcast(ctx, 22,
            Intent(ctx, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val canFullScreen = Build.VERSION.SDK_INT < 34 ||
            ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        val b = NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(icon)
            .setColor(AMBER)
            .setContentTitle(name)
            .setContentText(if (disguised) "Incoming call" else "Incoming Mura call · encrypted")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(fullScreen)
            .addAction(0, "Decline", decline)
            .addAction(0, "Answer", answer)
            .setTimeoutAfter(50_000)
        if (canFullScreen) b.setFullScreenIntent(fullScreen, true)
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        nm.notify(ID_CALL, n)
    }

    @SuppressLint("MissingPermission")
    fun ongoingCall(name: String) {
        if (!canPost()) return
        val hangup = PendingIntent.getBroadcast(ctx, 23,
            Intent(ctx, NotificationActionReceiver::class.java).setAction(NotificationActionReceiver.HANGUP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_MESH)
            .setSmallIcon(icon)
            .setColor(AMBER)
            .setContentTitle("On a call with $name")
            .setContentText("Direct Bluetooth link · encrypted")
            .setOngoing(true)
            .setUsesChronometer(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openIntent(EXTRA_CALL, "", 24))
            .addAction(0, "Hang up", hangup)
            .build()
        nm.notify(ID_ONGOING_CALL, n)
    }

    @SuppressLint("MissingPermission")
    fun missedCall(peerHex: String, name: String) {
        cancelCall()
        if (!canPost()) return
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(icon)
            .setColor(AMBER)
            .setContentTitle("Missed call")
            .setContentText(name)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setAutoCancel(true)
            .setContentIntent(openIntent(EXTRA_CHAT, peerHex, peerHex.hashCode() + 3))
            .build()
        nm.notify(peerHex.hashCode() + 3, n)
    }

    fun cancelCall() { nm.cancel(ID_CALL); nm.cancel(ID_ONGOING_CALL) }

}
