package com.acapan.island

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class IslandNotificationService : NotificationListenerService() {

    // key -> last "title|text", so repeated updates of the same notification don't re-trigger the island
    private val lastSeen = HashMap<String, String>()

    // Packages to ignore. Add apps here that you don't want on the island.
    private val ignored = setOf("android", "com.android.systemui")

    // Notification categories where the system island is already doing its own thing.
    private val yieldCategories = setOf(
        Notification.CATEGORY_CALL,
        Notification.CATEGORY_ALARM,
        "navigation",
        "stopwatch"
    )

    // ---- media session tracking (to detect "a song is playing")
    private var sessionManager: MediaSessionManager? = null
    private val controllers = mutableListOf<MediaController>()
    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshYield()
        override fun onSessionDestroyed() = refreshYield()
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        watchMedia(list)
    }

    override fun onListenerConnected() {
        if (Prefs.alwaysOn(this)) IslandController.start(this)
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            val me = ComponentName(this, IslandNotificationService::class.java)
            sessionManager = msm
            msm.addOnActiveSessionsChangedListener(sessionsListener, me)
            watchMedia(msm.getActiveSessions(me))
        } catch (_: Exception) {
        }
        refreshYield()
    }

    override fun onListenerDisconnected() {
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        controllers.forEach { it.unregisterCallback(mediaCallback) }
        controllers.clear()
        IslandController.stop()
    }

    private fun watchMedia(list: List<MediaController>?) {
        controllers.forEach { it.unregisterCallback(mediaCallback) }
        controllers.clear()
        if (list != null) controllers.addAll(list)
        controllers.forEach { it.registerCallback(mediaCallback) }
        refreshYield()
    }

    /** Hide the idle pill while music is playing or a call/alarm/navigation notification is active. */
    private fun refreshYield(excludeKey: String? = null) {
        val playing = controllers.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val special = try {
            activeNotifications?.any {
                it.key != excludeKey && it.notification.category in yieldCategories
            } == true
        } catch (_: Exception) {
            false
        }
        IslandController.setYield(this, playing || special)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        refreshYield()

        val n = sbn.notification
        val pkg = sbn.packageName

        if (pkg == packageName || pkg in ignored) return
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        // Ongoing = music players, downloads, "app is running" etc. The system island already handles some of these.
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val signature = "$title|$text"
        if (lastSeen[sbn.key] == signature) return
        lastSeen[sbn.key] = signature

        val pm = packageManager
        val appName = try {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        val icon = n.getLargeIcon()?.loadDrawable(this)
            ?: try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }

        IslandController.show(
            this,
            IslandData(
                appName = appName,
                title = title.ifBlank { appName },
                text = text,
                icon = icon,
                onOpen = { open(n, pkg) }
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        lastSeen.remove(sbn.key)
        refreshYield(excludeKey = sbn.key)
    }

    private fun open(n: Notification, pkg: String) {
        val pi = n.contentIntent
        if (pi != null) {
            try {
                pi.send()
                return
            } catch (_: PendingIntent.CanceledException) {
            }
        }
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
