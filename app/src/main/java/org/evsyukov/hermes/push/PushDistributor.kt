package org.evsyukov.hermes.push

import android.content.Context
import android.content.pm.PackageManager
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.UnifiedPush.VapidNotValidException

/** A UnifiedPush distributor installed on the device: its package and the name of its app. */
data class Distributor(val packageName: String, val label: String)

/** The UnifiedPush distributors of the device; [UnifiedPushDistributor] in the app, a fake in tests. */
interface PushDistributor {
    fun installed(): List<Distributor>

    /** The distributor chosen before, if it is still installed. */
    fun saved(): String?

    fun save(packageName: String)

    /**
     * Asks the saved distributor for an endpoint of [instance]; it answers through the push
     * service. False when it cannot be asked, e.g. with a key that is not a VAPID key.
     */
    fun register(instance: String, name: String, vapid: String): Boolean

    fun unregister(instance: String)
}

class UnifiedPushDistributor(private val context: Context) : PushDistributor {
    override fun installed(): List<Distributor> =
        UnifiedPush.getDistributors(context).filter { it != context.packageName }.map { Distributor(it, label(it)) }

    override fun saved(): String? = UnifiedPush.getSavedDistributor(context)

    override fun save(packageName: String) = UnifiedPush.saveDistributor(context, packageName)

    override fun register(instance: String, name: String, vapid: String): Boolean = try {
        UnifiedPush.register(context, instance, messageForDistributor = name, vapid = vapid)
        true
    } catch (e: VapidNotValidException) {
        false
    }

    override fun unregister(instance: String) = UnifiedPush.unregister(context, instance)

    private fun label(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }
}
