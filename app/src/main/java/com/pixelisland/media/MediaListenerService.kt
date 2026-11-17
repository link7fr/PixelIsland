package com.pixelisland.media

import android.service.notification.NotificationListenerService

/**
 * Exists so the system grants us access to [android.media.session.MediaSessionManager.getActiveSessions].
 * All real work happens in [MediaRepository].
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        MediaRepository.attachSessions(this)
    }

    override fun onListenerDisconnected() {
        MediaRepository.detachSessions()
        super.onListenerDisconnected()
    }
}
