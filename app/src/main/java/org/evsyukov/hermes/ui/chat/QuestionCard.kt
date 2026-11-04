package org.evsyukov.hermes.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.QuestionCard
import org.evsyukov.hermes.chat.QuestionState
import org.evsyukov.hermes.ui.theme.ChatTheme

/** What the card of a question can ask of the chat. */
class QuestionActions(
    val answer: (id: String, Map<String, String?>) -> Unit,
    val cancel: (id: String) -> Unit,
)

/**
 * A question of the agent in the transcript. Pending, it takes a choice or words for each of its
 * questions in [answers], and only its own two buttons answer or cancel it: scrolling, swiping, Back
 * or leaving the chat do neither. Settled, it keeps the questions with what was answered and says
 * how it ended.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentQuestion(card: QuestionCard, answers: SnapshotStateMap<String, String>, connected: Boolean, actions: QuestionActions) {
    val colors = ChatTheme.colors
    val pending = card.state == QuestionState.PENDING
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape)
            .border(if (pending) 2.dp else 1.dp, if (pending) colors.contrast else colors.composerBorder, shape)
            .padding(14.dp).testTag("question:${card.id}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = if (pending) colors.text else colors.secondaryText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.chat_clarify_title),
                color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
        }
        card.questions.forEachIndexed { index, question ->
            if (card.questions.size > 1) {
                Text(stringResource(R.string.chat_question_number, index + 1, card.questions.size), color = colors.faintText, fontSize = 12.sp)
            }
            Text(question.text, color = colors.text, fontSize = 15.sp)
            when {
                pending -> {
                    if (question.choices.isNotEmpty()) {
                        // Long choices wrap rather than run off a narrow screen.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            question.choices.forEach { choice ->
                                FilterChip(selected = answers[question.id] == choice, onClick = { answers[question.id] = choice }, label = { Text(choice) })
                            }
                        }
                    }
                    OutlinedTextField(
                        answers[question.id].orEmpty(), { answers[question.id] = it },
                        label = { Text(stringResource(R.string.chat_own_answer)) },
                        modifier = Modifier.fillMaxWidth().testTag("answer:${question.id}"),
                    )
                }
                card.state == QuestionState.SUBMITTED -> {
                    val given = card.answers[question.id]
                    Text(
                        given ?: stringResource(R.string.chat_question_no_answer),
                        color = if (given != null) colors.text else colors.faintText, fontSize = 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.userBubble).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        if (pending) {
            // Until the chat's join confirms the request, it may be one the server has ended: the words stay, the buttons wait.
            val open = connected && card.confirmed
            when {
                !connected -> Text(stringResource(R.string.chat_question_offline), color = colors.warning, fontSize = 13.sp)
                !card.confirmed -> Text(stringResource(R.string.chat_question_checking), color = colors.secondaryText, fontSize = 13.sp)
            }
            Button(
                onClick = { actions.answer(card.id, card.questions.associate { it.id to answers[it.id] }) },
                enabled = open && card.questions.any { !answers[it.id].isNullOrBlank() },
                modifier = Modifier.fillMaxWidth().testTag("question-answer"),
                colors = ButtonDefaults.buttonColors(containerColor = colors.contrast, contentColor = colors.onContrast),
            ) { Text(stringResource(R.string.chat_answer)) }
            TextButton(onClick = { actions.cancel(card.id) }, enabled = open, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.chat_question_cancel), color = if (open) colors.danger else colors.faintText)
            }
        } else {
            val outcome = when (card.state) {
                QuestionState.PENDING, QuestionState.SUBMITTED -> R.string.chat_question_answered
                QuestionState.CANCELLED -> R.string.chat_question_cancelled
                QuestionState.EXPIRED -> R.string.chat_question_expired
                QuestionState.CLOSED -> R.string.chat_question_closed
            }
            Text(
                stringResource(outcome),
                color = colors.secondaryText, fontSize = 13.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
