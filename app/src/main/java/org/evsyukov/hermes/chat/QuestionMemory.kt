package org.evsyukov.hermes.chat

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap

/**
 * What the app knows of the questions of the agent on one server connection, kept in memory for as long
 * as that connection is the app's: the cards of each chat with their outcome, the requests the server
 * ended, the connection over which each request was last heard of, and the answers written on the cards
 * and not sent. A chat is closed whenever another one opens; opened again, it finds its questions as they
 * were. Another server, profile or route is another connection and starts with nothing. Everything is
 * kept by chat and request, so one chat never sees the questions of another. Questions and their
 * answers are words the user reads in the chat; no secret is asked through them.
 */
class QuestionMemory {
    private val lock = Any()
    private val cards = HashMap<String, List<QuestionCard>>()
    private val heard = HashMap<Pair<String, String>, Int>()
    private val ended = HashMap<Pair<String, String>, QuestionState>()
    private val answers = HashMap<Pair<String, String>, SnapshotStateMap<String, String>>()

    /** The cards of chat [chat], oldest first. */
    fun cards(chat: String): List<QuestionCard> = synchronized(lock) { cards[chat].orEmpty() }

    fun keep(chat: String, cards: List<QuestionCard>) = synchronized(lock) { this.cards[chat] = cards }

    /** The connection over which the server last listed request [id] as open, or its answer went. */
    fun heardOn(chat: String, id: String): Int? = synchronized(lock) { heard[chat to id] }

    fun heard(chat: String, id: String, generation: Int) = synchronized(lock) { heard[chat to id] = generation }

    /** How the server ended request [id], or null while it has not: an ended request never opens again. */
    fun ended(chat: String, id: String): QuestionState? = synchronized(lock) { ended[chat to id] }

    fun end(chat: String, id: String, state: QuestionState) = synchronized(lock) { ended[chat to id] = state }

    /** The answers written on the card of request [id]; a card seen for the first time starts with [prefilled]. */
    fun answers(chat: String, id: String, prefilled: Map<String, String> = emptyMap()): SnapshotStateMap<String, String> =
        synchronized(lock) { answers.getOrPut(chat to id) { mutableStateMapOf<String, String>().apply { putAll(prefilled) } } }

    /** Drops everything of chat [chat], which the user deleted. */
    fun forget(chat: String) = synchronized(lock) {
        cards.remove(chat)
        heard.keys.removeAll { it.first == chat }
        ended.keys.removeAll { it.first == chat }
        answers.keys.removeAll { it.first == chat }
    }
}
