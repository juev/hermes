package org.evsyukov.hermes.ui.chat

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.remote.SessionUsage
import org.evsyukov.hermes.ui.common.dollars
import org.evsyukov.hermes.ui.common.tokenCount
import org.evsyukov.hermes.ui.theme.ChatTheme

private const val SEPARATOR = " · "

/**
 * Whether the spent line stays beside the model or moves under it: the two fit side by side only
 * when the model, the separator and the line are no wider than the room the header has.
 */
internal fun spentBesideModel(modelWidth: Int, spentWidth: Int, separatorWidth: Int, available: Int): Boolean =
    modelWidth + separatorWidth + spentWidth <= available

/** What the chat spent, as the header prints it: Chat: 12.4 K in · 8.2 K out · $0.12. */
@Composable
internal fun usageText(usage: SessionUsage): String {
    val tokens = stringResource(R.string.chat_usage, tokenCount(usage.input), tokenCount(usage.output))
    val cost = usage.costUsd?.let(::dollars) ?: if (usage.included) stringResource(R.string.chat_usage_included) else null
    return stringResource(R.string.chat_usage_scope, listOfNotNull(tokens, cost).joinToString(SEPARATOR))
}

/**
 * The model and what the chat has spent, under the title. One line while the two fit the room the
 * header gives them, and the spent line under the model when they do not: a long name, as the
 * Codex models have, then keeps every number readable instead of running off the screen. Each of
 * the two lines is one line at most, so a name too long even for its own line gives way, not the
 * numbers.
 *
 * The totals are the chat's, not the model's: the server keeps one running total for the session,
 * and it keeps growing when the chat switches model. [pending] marks a model picked while a turn
 * runs, which the server applies at the next turn.
 */
@Composable
internal fun ChatSubtitle(model: String?, usage: SessionUsage?, pending: Boolean = false, modifier: Modifier = Modifier) {
    if (model == null && usage == null) return
    val style = TextStyle(color = ChatTheme.colors.faintText, fontSize = 12.sp)
    val spent = usage?.let { usageText(it) }
    val shownModel = model?.let { if (pending) it + SEPARATOR + stringResource(R.string.chat_model_next_turn) else it }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val beside = shownModel == null || spent == null || !constraints.hasBoundedWidth || spentBesideModel(
            measurer.measure(shownModel, style, maxLines = 1).size.width,
            measurer.measure(spent, style, maxLines = 1).size.width,
            measurer.measure(SEPARATOR, style, maxLines = 1).size.width,
            constraints.maxWidth,
        )
        when {
            shownModel == null -> Line(spent.orEmpty(), style)
            spent == null || beside -> Line(if (spent == null) shownModel else shownModel + SEPARATOR + spent, style)
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Line(shownModel, style)
                Line(spent, style)
            }
        }
    }
}

@Composable
private fun Line(text: String, style: TextStyle) {
    Text(text, style = style, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
