package org.evsyukov.hermes.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.evsyukov.hermes.data.remote.ServerRequest
import org.evsyukov.hermes.data.remote.string

/** One question of a `clarify` request: words to write, or one of [choices]. */
data class Question(val id: String, val text: String, val choices: List<String>)

/** Where a question of the agent stands. Only a pending one takes an answer. */
enum class QuestionState {
    PENDING,

    /** The user's answer went to the server. */
    SUBMITTED,

    /** The user said on the card that they will not answer. */
    CANCELLED,

    /** The server stopped waiting at its deadline: `request.cancel` with the reason `timeout`. */
    EXPIRED,

    /**
     * The server stopped waiting for another reason: answered elsewhere, the turn interrupted, the
     * session closed, or no longer open when the chat joined again after a lost connection.
     */
    CLOSED,
}

/**
 * A `clarify` request of the agent as the chat shows it: a card of the transcript that takes an answer
 * while it is pending, and keeps the questions and what was answered once it is not.
 */
data class QuestionCard(
    /** The id of the server's request: the answer goes back under it. */
    val id: String,
    val questions: List<Question>,
    val state: QuestionState = QuestionState.PENDING,
    /** What was answered, by question id; null for a question left without an answer. Only a submitted card has it. */
    val answers: Map<String, String?> = emptyMap(),
    /** The tool the agent was running when it asked: the card stands under the answer that holds it. */
    val toolCallId: String? = null,
    /**
     * The answers the card starts with: those the server already locked, as a reconnect reports them,
     * or the ones of an answer that was lost with its connection.
     */
    val prefilled: Map<String, String> = emptyMap(),
    /** When the chat first saw the request, in epoch seconds by the clock of the phone. */
    val time: Double? = null,
    /**
     * The join of the chat on the screen confirmed the server holds the request open: listed by the
     * join, or asked on its session since. Only a confirmed pending card takes an answer; one kept
     * from an earlier visit or an earlier connection waits for the join.
     */
    val confirmed: Boolean = false,
)

/** The card of a `clarify` request first seen at [time]; [toolCallId] is the tool the agent was running when it asked. */
fun questionCardOf(request: ServerRequest, toolCallId: String?, time: Double? = null): QuestionCard {
    val params = request.params
    return QuestionCard(
        id = request.id,
        questions = (params["questions"] as? JsonArray)?.mapNotNull { q ->
            val obj = q as? JsonObject ?: return@mapNotNull null
            Question(
                id = obj.string("qid").orEmpty(),
                text = obj.string("question").orEmpty(),
                choices = (obj["choices"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            )
        }.orEmpty(),
        toolCallId = toolCallId,
        // A reconnect lists the answers the server has locked so far; a null one is a question skipped.
        prefilled = (params["answers"] as? JsonObject)?.mapNotNull { (qid, answer) ->
            (answer as? JsonPrimitive)?.takeIf { it.isString }?.let { qid to it.content }
        }?.toMap().orEmpty(),
        time = time,
    )
}

/** The tool the last answer ran last: the one a question that comes now belongs to. */
internal fun lastTool(items: List<ChatItem>): String? =
    ((items.lastOrNull() as? ChatItem.Assistant)?.parts?.lastOrNull { it is Part.Tool } as? Part.Tool)?.run?.id
