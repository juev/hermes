package org.evsyukov.hermes.chat

/**
 * The messages the user has sent that have no outcome yet, each with the point at which it last
 * moved. A join answer is as old as the point at which it was asked: a send that entered or went to
 * the server after that point is newer than the answer, and an idle answer cannot say it ended. A
 * send still waiting to go to the server when the answer was asked is newer too, whatever its
 * point: the server's answer was taken before the prompt could reach it. Intents that ended are
 * dropped, so a failure from long ago cannot make a fresh idle answer look older than it is.
 *
 * The events carry no turn id. A start belongs to the oldest submitted intent that has not started;
 * a terminal outcome belongs to a started one. An outcome with none started is of an older or
 * adopted turn and leaves the local intents alone. That attribution is the limit of what the
 * protocol reports; it is not a claim about a turn the server identifies.
 */
internal class SendActivityTracker {
    private enum class Phase { PENDING, SUBMITTED, STARTED }

    private class Activity(val id: Long, var phase: Phase, var at: Long)

    private val lock = Any()
    private var seq = 0L
    private val activities = ArrayList<Activity>()

    /** The user started a send; its id goes with the coroutine that takes it to the server. */
    fun begin(): Long = synchronized(lock) {
        val id = ++seq
        activities += Activity(id, Phase.PENDING, id)
        id
    }

    /** The send of [id] went to the server; from now on it waits for a start. */
    fun submitted(id: Long) = synchronized(lock) {
        val activity = activities.firstOrNull { it.id == id } ?: return@synchronized
        activity.phase = Phase.SUBMITTED
        activity.at = ++seq
        // In the order the app issued the submissions: the oldest submitted intent takes the next
        // start. The protocol reports no turn id, so this is the app's order, not a server guarantee.
        activities.remove(activity)
        activities += activity
    }

    /** The server started a turn: the oldest submitted send is the one waiting for it. */
    fun started() = synchronized(lock) {
        activities.firstOrNull { it.phase == Phase.SUBMITTED }?.let {
            it.phase = Phase.STARTED
            it.at = ++seq
        }
    }

    /** A turn ended: the oldest started send is the one it was. An adopted turn has no send here. */
    fun ended() = synchronized(lock) {
        activities.firstOrNull { it.phase == Phase.STARTED }?.let { activities.remove(it) }
    }

    /** The send of [id] failed or was stopped before it had a turn. */
    fun finish(id: Long) = synchronized(lock) { activities.removeAll { it.id == id } }

    /** The user stopped: no local send is still to be expected. */
    fun stopAll() = synchronized(lock) { activities.clear() }

    /** The point at which a join answer is asked, for [protects] and [discard]. */
    fun mark(): Long = synchronized(lock) { seq }

    /** Whether an idle answer asked at [mark] is older than a local send it cannot speak for. */
    fun protects(mark: Long): Boolean = synchronized(lock) {
        activities.any { it.phase == Phase.PENDING || it.at > mark }
    }

    /**
     * The answer at [mark] is newer than every local send and says the server is idle: those sends
     * have ended, including one whose outcome was lost while the chat was disconnected.
     */
    fun discard(mark: Long) = synchronized(lock) {
        activities.removeAll { it.phase != Phase.PENDING && it.at <= mark }
    }
}
