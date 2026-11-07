package org.evsyukov.hermes.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.plainText
import org.evsyukov.hermes.data.remote.SearchHit
import org.evsyukov.hermes.ui.SearchRequestIdentity
import org.evsyukov.hermes.ui.SearchState
import org.evsyukov.hermes.ui.theme.ChatTheme
import java.text.DateFormat
import java.util.Date

/** Full-text search over every chat of the server, the FTS5 index of Hermes doing the work. */
@Composable
fun SearchScreen(
    state: SearchState,
    onQuery: (String) -> Unit,
    onOpen: (SearchHit, SearchRequestIdentity?) -> Unit,
    onBack: () -> Unit,
    /** Where the results list opens, and whether the field may take the keyboard. */
    initialIndex: Int = 0,
    initialOffset: Int = 0,
    requestFocusOnOpen: Boolean = true,
    /** The place the results list shows now, for the return after a result is opened. */
    onListPosition: (Int, Int) -> Unit = { _, _ -> },
) {
    val colors = ChatTheme.colors
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex, initialFirstVisibleItemScrollOffset = initialOffset)
    LaunchedEffect(Unit) { if (requestFocusOnOpen) focus.requestFocus() }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> onListPosition(index, offset) }
    }
    Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back), tint = colors.text) }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(colors.userBubble).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    val label = stringResource(R.string.search_placeholder)
                    BasicTextField(
                        state.query, onQuery,
                        singleLine = true,
                        textStyle = TextStyle(color = colors.text, fontSize = 16.sp),
                        cursorBrush = SolidColor(colors.text),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = label },
                    )
                    // The hint supplies the visible placeholder without taking part in measurement, so the
                    // field itself keeps one height and does not move when the text appears.
                    if (state.query.isEmpty()) {
                        Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterStart) {
                            Text(
                                label, color = colors.faintText, fontSize = 16.sp,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
                        }
                    }
                }
                if (state.query.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.Close, stringResource(R.string.search_clear), tint = colors.faintText,
                        modifier = Modifier.clickable(role = Role.Button) { onQuery("") },
                    )
                }
            }
        }
        if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        state.error?.let { Text(it, color = colors.dangerText, modifier = Modifier.padding(16.dp)) }
        if (state.query.isNotBlank() && state.request != null && !state.searching && state.hits.isEmpty() && state.error == null) {
            Text(stringResource(R.string.search_empty), color = colors.faintText, modifier = Modifier.padding(16.dp))
        }
        if (state.query.isBlank()) {
            Text(
                stringResource(R.string.search_help),
                color = colors.faintText, fontSize = 14.sp, modifier = Modifier.padding(16.dp),
            )
        }
        if (state.hits.isNotEmpty()) {
            Text(
                stringResource(R.string.search_result_hint),
                color = colors.faintText, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(state.hits, key = { it.chat.id + it.snippet.hashCode() }) { hit ->
                HitRow(hit, state.query) { onOpen(hit, state.request) }
                HorizontalDivider(color = colors.divider)
            }
        }
    }
}

@Composable
private fun HitRow(hit: SearchHit, query: String, onClick: () -> Unit) {
    val colors = ChatTheme.colors
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(hit.chat.label, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                DateFormat.getDateInstance(DateFormat.SHORT).format(Date((hit.chat.lastActive * 1000).toLong())),
                color = colors.faintText, fontSize = 12.sp,
            )
        }
        if (hit.snippet.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(highlight(hit.snippet, query, colors.text), color = colors.secondaryText, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Marks the words of the query in the snippet; FTS snippets come with `>>>`/`<<<` or plain. */
private fun highlight(snippet: String, query: String, color: androidx.compose.ui.graphics.Color): AnnotatedString {
    val clean = plainText(snippet.replace(">>>", "").replace("<<<", "")).replace("\n", " ")
    val words = query.split(' ', '"').map { it.trim('*') }.filter { it.length >= 2 && it.uppercase() !in setOf("OR", "AND", "NOT") }
    return buildAnnotatedString {
        var i = 0
        val lower = clean.lowercase()
        while (i < clean.length) {
            val match = words.mapNotNull { w -> lower.indexOf(w.lowercase(), i).takeIf { it >= 0 }?.let { it to w.length } }.minByOrNull { it.first }
            if (match == null) {
                append(clean.substring(i))
                break
            }
            append(clean.substring(i, match.first))
            withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(clean.substring(match.first, match.first + match.second)) }
            i = match.first + match.second
        }
    }
}
