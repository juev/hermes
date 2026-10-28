package org.evsyukov.hermes.push

import kotlinx.coroutines.launch
import org.evsyukov.hermes.HermesApplication
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage as UnifiedPushMessage

/** Events of the UnifiedPush distributor; the instance is the id of the server. */
class HermesPushService : PushService() {
    private val container get() = (application as HermesApplication).container

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        // Without keys the server could not encrypt to the app: such an endpoint is of no use.
        val keys = endpoint.pubKeySet ?: return
        container.scope.launch { container.push.onNewEndpoint(instance, endpoint.url, keys.pubKey, keys.auth) }
    }

    override fun onMessage(message: UnifiedPushMessage, instance: String) {
        if (!message.decrypted) return
        container.scope.launch { container.pushMessages.handle(message.content) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        container.scope.launch { container.push.onFailed(instance) }
    }

    override fun onUnregistered(instance: String) {
        container.scope.launch { container.push.onUnregistered(instance) }
    }
}
