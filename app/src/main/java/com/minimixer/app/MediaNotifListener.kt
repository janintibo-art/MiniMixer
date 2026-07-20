package com.minimixer.app

import android.service.notification.NotificationListenerService

/**
 * Service d'accès aux sessions média. Prévient l'activité dès que le système
 * le (re)branche, pour rafraîchir les tranches automatiquement.
 */
class MediaNotifListener : NotificationListenerService() {

    companion object {
        @Volatile
        var onConnected: (() -> Unit)? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        onConnected?.invoke()
    }
}
