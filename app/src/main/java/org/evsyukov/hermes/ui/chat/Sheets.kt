package org.evsyukov.hermes.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.ApprovalKind
import org.evsyukov.hermes.chat.Prompt
import org.evsyukov.hermes.data.remote.ModelChoices
import org.evsyukov.hermes.data.remote.ModelProvider
import org.evsyukov.hermes.ui.theme.ChatTheme

@Composable
private fun choiceLabel(choice: String): String = when (choice) {
    "once" -> stringResource(R.string.chat_choice_once)
    "session" -> stringResource(R.string.chat_choice_session)
    "always" -> stringResource(R.string.chat_choice_always)
    "deny" -> stringResource(R.string.chat_choice_deny)
    else -> choice
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptSheet(
    prompt: Prompt,
    onApproval: (String) -> Unit,
    onClarify: (Map<String, String?>) -> Unit,
    onSecret: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = ChatTheme.colors
    ModalBottomSheet(onDismissRequest = onCancel, containerColor = colors.background) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (prompt) {
                is Prompt.Approval -> {
                    val title = when (val kind = prompt.kind) {
                        ApprovalKind.Command -> stringResource(R.string.chat_approval_title)
                        is ApprovalKind.Tool -> stringResource(R.string.chat_approval_tool_title, kind.name)
                        ApprovalKind.Mcp -> stringResource(R.string.chat_approval_mcp_title)
                    }
                    Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                    // The server's question is prose, unlike a command.
                    if (prompt.kind == ApprovalKind.Mcp && prompt.command.isNotBlank()) Text(prompt.command, color = colors.text)
                    if (prompt.description.isNotBlank()) Text(prompt.description, color = colors.secondaryText)
                    if (prompt.kind == ApprovalKind.Command && prompt.command.isNotBlank()) {
                        SelectionContainer {
                            Text(
                                prompt.command,
                                fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = colors.text,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.codeBackground)
                                    .heightIn(max = 240.dp).horizontalScroll(rememberScrollState()).padding(12.dp),
                            )
                        }
                    }
                    prompt.choices.filter { it != "deny" }.forEachIndexed { index, choice ->
                        if (index == 0) {
                            Button(
                                onClick = { onApproval(choice) }, modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.contrast, contentColor = colors.onContrast),
                            ) { Text(choiceLabel(choice)) }
                        } else {
                            OutlinedButton(onClick = { onApproval(choice) }, modifier = Modifier.fillMaxWidth()) { Text(choiceLabel(choice), color = colors.text) }
                        }
                    }
                    TextButton(onClick = { onApproval("deny") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.chat_choice_deny), color = colors.danger) }
                }
                is Prompt.Clarify -> {
                    val answers = remember { mutableStateMapOf<String, String>() }
                    Text(stringResource(R.string.chat_clarify_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                    prompt.questions.forEach { question ->
                        Text(question.text, color = colors.text)
                        if (question.choices.isNotEmpty()) {
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                question.choices.forEach { choice ->
                                    FilterChip(selected = answers[question.id] == choice, onClick = { answers[question.id] = choice }, label = { Text(choice) })
                                }
                            }
                        }
                        OutlinedTextField(
                            answers[question.id].orEmpty(), { answers[question.id] = it },
                            placeholder = { Text(stringResource(R.string.chat_own_answer)) }, modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Button(
                        onClick = { onClarify(prompt.questions.associate { it.id to answers[it.id] }) }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.contrast, contentColor = colors.onContrast),
                    ) { Text(stringResource(R.string.chat_answer)) }
                }
                is Prompt.Secret -> {
                    var value by remember { mutableStateOf("") }
                    Text(stringResource(if (prompt.sudo) R.string.chat_need_sudo else R.string.chat_need_secret), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
                    Text(prompt.title, color = colors.secondaryText)
                    OutlinedTextField(value, { value = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = { onSecret(value) }, enabled = value.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.contrast, contentColor = colors.onContrast),
                    ) { Text(stringResource(R.string.chat_submit)) }
                    TextButton(onClick = { onSecret(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.chat_refuse)) }
                }
            }
        }
    }
}

/**
 * The providers and models whose name contains [query], in the order the server gave. A provider
 * with no matching model is left out, so its heading never sits over an empty list; an empty query
 * gives the whole list back.
 */
internal fun matchingProviders(choices: ModelChoices, query: String): List<ModelProvider> {
    val needle = query.trim()
    if (needle.isEmpty()) return choices.providers
    return choices.providers.mapNotNull { provider ->
        val models = provider.models.filter { it.contains(needle, ignoreCase = true) }
        if (models.isEmpty()) null else provider.copy(models = models)
    }
}

/** The providers, models and their filter; apart from the sheet so it can be tested on its own. */
@Composable
internal fun ModelList(choices: ModelChoices, current: String?, onPick: (String, String) -> Unit) {
    val colors = ChatTheme.colors
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.chat_models_filter)) },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.search_clear)) }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("model-filter"),
        )
        val shown = matchingProviders(choices, query)
        if (shown.isEmpty()) {
            Text(
                stringResource(R.string.chat_models_no_match, query.trim()),
                color = colors.faintText, fontSize = 15.sp, modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                shown.forEach { provider ->
                    item(key = "p:${provider.slug}") {
                        Text(
                            provider.name,
                            color = colors.faintText, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(provider.models, key = { "${provider.slug}/$it" }) { model ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(provider.slug, model) }.padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(model, color = colors.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                            if (model == current) Icon(Icons.Outlined.Check, null, tint = colors.text, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                item { Spacer(Modifier.size(24.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(current: String?, load: suspend () -> ModelChoices, onPick: (String, String) -> Unit, onDismiss: () -> Unit) {
    val colors = ChatTheme.colors
    val choices by produceState<Result<ModelChoices>?>(null) { value = runCatching { load() } }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Text(stringResource(R.string.common_model), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        val result = choices
        when {
            result == null -> CircularProgressIndicator(Modifier.padding(32.dp).align(Alignment.CenterHorizontally))
            result.isFailure -> Text(stringResource(R.string.chat_models_failed, result.exceptionOrNull()?.message.orEmpty()), color = colors.danger, modifier = Modifier.padding(20.dp))
            else -> ModelList(result.getOrThrow(), current, onPick)
        }
    }
}
