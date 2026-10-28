package org.evsyukov.hermes.ui.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.remote.AdminApi
import org.evsyukov.hermes.data.remote.ConfigField
import org.evsyukov.hermes.data.remote.HermesJson
import org.evsyukov.hermes.data.remote.bool
import org.evsyukov.hermes.data.remote.string
import org.evsyukov.hermes.ui.theme.ChatTheme

@Composable
fun ModelScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    val info = rememberLoadable { api.modelInfo() }
    val options = rememberLoadable { api.modelOptions() }
    var confirm by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    val context = LocalContext.current

    fun pick(provider: String, model: String, confirmed: Boolean = false) = scope.attempt(snackbar) {
        val result = api.setModel(provider, model, confirmed)
        if (result.bool("confirm_required") == true) {
            confirm = Triple(provider, model, result.string("confirm_message") ?: context.getString(R.string.admin_model_expensive))
        } else {
            snackbar.showSnackbar(result.string("notice") ?: context.getString(R.string.admin_model_new_chats, model))
            info.reload()
            options.reload()
        }
    }

    AdminScaffold(stringResource(R.string.admin_model), onBack, info.loading || options.loading, { info.reload(); options.reload() }, snackbar) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                info.value?.let { m ->
                    Card {
                        KeyValue(stringResource(R.string.admin_model_now), m.string("model").orEmpty())
                        KeyValue(stringResource(R.string.admin_model_provider), m.string("provider").orEmpty())
                        m.string("effective_context_length")?.let { KeyValue(stringResource(R.string.admin_model_context), it) }
                    }
                }
                Text(
                    stringResource(R.string.admin_model_info),
                    color = colors.faintText, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                )
                ErrorText(info.error ?: options.error)
            }
            options.value?.providers?.forEach { provider ->
                item(key = "p:${provider.slug}") {
                    SectionTitle(provider.name + if (provider.current) stringResource(R.string.admin_model_current) else "")
                    provider.warning?.let { Text(it, color = colors.warning, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
                }
                items(provider.models, key = { "${provider.slug}/$it" }) { model ->
                    val current = model == info.value?.string("model") && provider.slug == info.value.string("provider")
                    ClickRow(model, onClick = { pick(provider.slug, model) }) {
                        if (current) Icon(Icons.Outlined.Check, null, tint = colors.text)
                    }
                }
            }
        }
    }
    confirm?.let { (provider, model, message) ->
        ConfirmDialog(stringResource(R.string.admin_model_expensive_title), message, stringResource(R.string.chat_switch), { pick(provider, model, confirmed = true) }) { confirm = null }
    }
}

@Composable
fun ConfigScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    val schema = rememberLoadable { api.schema() }
    val config = rememberLoadable { api.config() }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ConfigField?>(null) }
    var raw by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AdminScaffold(
        stringResource(R.string.admin_config), onBack, schema.loading || config.loading, { schema.reload(); config.reload() }, snackbar,
        actions = { IconButton(onClick = { raw = true }) { Icon(Icons.Outlined.Code, stringResource(R.string.admin_config_raw)) } },
    ) {
        val fields = schema.value?.fields.orEmpty()
        val categories = remember(schema.value) {
            val order = schema.value?.categoryOrder.orEmpty()
            fields.map { it.category }.distinct().sortedBy { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        }
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                query, { query = it }, placeholder = { Text(stringResource(R.string.admin_config_search)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(category == null, { category = null }, label = { Text(stringResource(R.string.common_all)) })
                categories.forEach { c -> FilterChip(category == c, { category = c }, label = { Text(c) }) }
            }
            ErrorText(schema.error ?: config.error)
            val shown = fields.filter { f ->
                (category == null || f.category == category) &&
                    (query.isBlank() || f.path.contains(query, true) || f.description.contains(query, true))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.path }) { field ->
                    val value = config.value?.let { AdminApi.valueAt(it, field.path) } ?: JsonNull
                    ClickRow(field.path, field.description.takeIf { it != field.path }, onClick = { editing = field }) {
                        Text(display(value), color = colors.secondaryText, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp).widthIn(max = 140.dp))
                    }
                    HorizontalDivider(color = colors.divider)
                }
            }
        }
    }

    editing?.let { field ->
        val current = config.value?.let { AdminApi.valueAt(it, field.path) } ?: JsonNull
        FieldEditor(field, current, onDismiss = { editing = null }) { value ->
            editing = null
            scope.attempt(snackbar, context.getString(R.string.admin_config_saved, field.path)) {
                api.setConfig(field.path, value)
                config.reload()
            }
        }
    }
    if (raw) RawConfigDialog(api, onDismiss = { raw = false }) { config.reload() }
}

private fun display(value: JsonElement): String = when (value) {
    is JsonNull -> "—"
    is JsonPrimitive -> value.content.take(40)
    else -> value.toString().take(40)
}

@Composable
private fun FieldEditor(field: ConfigField, current: JsonElement, onDismiss: () -> Unit, onSave: (JsonElement) -> Unit) {
    val colors = ChatTheme.colors
    val structured = field.type == "list" || field.type == "object" || current is JsonObject || current is kotlinx.serialization.json.JsonArray
    var text by remember {
        mutableStateOf(
            when {
                current is JsonNull -> ""
                current is JsonPrimitive -> current.content
                else -> HermesJson.encodeToString(JsonElement.serializer(), current)
            },
        )
    }
    var flag by remember { mutableStateOf((current as? JsonPrimitive)?.content == "true") }
    var error by remember { mutableStateOf<String?>(null) }
    val unparsed = stringResource(R.string.admin_config_unparsed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.path, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (field.description != field.path) Text(field.description, color = colors.secondaryText, fontSize = 14.sp)
                when {
                    field.type == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(if (flag) R.string.admin_config_on else R.string.admin_config_off), modifier = Modifier.weight(1f))
                        Switch(flag, { flag = it })
                    }
                    field.options.isNotEmpty() -> {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            OutlinedTextField(
                                text, {}, readOnly = true,
                                trailingIcon = { IconButton(onClick = { open = true }) { Icon(Icons.Outlined.ArrowDropDown, null) } },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            DropdownMenu(open, { open = false }) {
                                field.options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { text = option; open = false }) }
                            }
                        }
                    }
                    else -> OutlinedTextField(
                        text, { text = it; error = null },
                        textStyle = if (structured) TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp) else TextStyle.Default,
                        minLines = if (structured) 4 else 1,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    )
                }
                if (structured) Text(stringResource(R.string.admin_config_json), color = colors.faintText, fontSize = 12.sp)
                error?.let { Text(it, color = colors.danger, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value: JsonElement? = when {
                    field.type == "boolean" -> JsonPrimitive(flag)
                    field.type == "number" -> text.trim().toLongOrNull()?.let(::JsonPrimitive) ?: text.trim().toDoubleOrNull()?.let(::JsonPrimitive)
                    structured -> runCatching { HermesJson.parseToJsonElement(text) }.getOrNull()
                    else -> JsonPrimitive(text)
                }
                if (value == null) error = unparsed else onSave(value)
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun RawConfigDialog(api: AdminApi, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val colors = ChatTheme.colors
    val scope = rememberCoroutineScope()
    val loaded = rememberLoadable { api.rawConfig() }
    var text by remember(loaded.value) { mutableStateOf(loaded.value.orEmpty()) }
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("config.yaml") },
        text = {
            Column {
                Text(stringResource(R.string.admin_config_raw_warning), color = colors.warning, fontSize = 13.sp)
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    text, { text = it },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 520.dp),
                )
                (error ?: loaded.error)?.let { Text(it, color = colors.danger, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(enabled = loaded.value != null && text != loaded.value, onClick = { confirm = true }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
    if (confirm) {
        ConfirmDialog(stringResource(R.string.admin_config_replace_title), stringResource(R.string.admin_config_replace_text), stringResource(R.string.admin_config_replace), {
            scope.launchCatching({ error = it }) {
                api.saveRawConfig(text)
                onSaved()
                onDismiss()
            }
        }) { confirm = false }
    }
}

@Composable
fun SecretsScreen(api: AdminApi, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    val env = rememberLoadable { api.env() }
    var query by remember { mutableStateOf("") }
    var onlySet by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var revealing by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf<Pair<String, String>?>(null) }
    val context = LocalContext.current

    AdminScaffold(stringResource(R.string.admin_secrets), onBack, env.loading, env.reload, snackbar) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.common_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(onlySet, { onlySet = true }, label = { Text(stringResource(R.string.admin_secrets_set)) })
                FilterChip(!onlySet, { onlySet = false }, label = { Text(stringResource(R.string.common_all)) })
                FilterChip(false, { editing = "" }, label = { Text(stringResource(R.string.admin_secrets_add)) })
            }
            ErrorText(env.error)
            val vars = env.value.orEmpty().entries
                .filter { (key, v) -> (!onlySet || (v as? JsonObject)?.bool("is_set") == true) && (query.isBlank() || key.contains(query, true)) }
                .sortedBy { it.key }
            LazyColumn(Modifier.fillMaxSize()) {
                items(vars, key = { it.key }) { (key, value) ->
                    val v = value as? JsonObject
                    val set = v?.bool("is_set") == true
                    ClickRow(key, v?.string("description") ?: v?.string("category"), onClick = { editing = key }) {
                        if (set) {
                            Text(v.string("redacted_value")?.removePrefix("«redacted:")?.removeSuffix("»") ?: "•••", color = colors.faintText, fontSize = 12.sp)
                            IconButton(onClick = { revealing = key }) { Icon(Icons.Outlined.Visibility, stringResource(R.string.admin_secrets_show), tint = colors.secondaryText) }
                            IconButton(onClick = { deleting = key }) { Icon(Icons.Outlined.Delete, stringResource(R.string.common_delete), tint = colors.danger) }
                        } else {
                            Text(stringResource(R.string.admin_secrets_not_set), color = colors.faintText, fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = colors.divider)
                }
            }
        }
    }
    editing?.let { key ->
        var name by remember(key) { mutableStateOf(key) }
        var value by remember(key) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (key.isEmpty()) stringResource(R.string.admin_secrets_new) else key) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (key.isEmpty()) OutlinedTextField(name, { name = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '_' } }, label = { Text(stringResource(R.string.admin_secrets_name)) }, singleLine = true)
                    OutlinedTextField(value, { value = it }, label = { Text(stringResource(R.string.admin_secrets_value)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Text(stringResource(R.string.admin_secrets_hint_env), color = colors.faintText, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && value.isNotEmpty(), onClick = {
                    editing = null
                    scope.attempt(snackbar, context.getString(R.string.admin_secrets_saved, name)) { api.setEnv(name, value); env.reload() }
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    deleting?.let { key ->
        ConfirmDialog(stringResource(R.string.admin_secrets_delete_title, key), stringResource(R.string.admin_secrets_delete_text), stringResource(R.string.common_delete), {
            scope.attempt(snackbar, context.getString(R.string.admin_secrets_deleted, key)) { api.deleteEnv(key); env.reload() }
        }) { deleting = null }
    }
    revealing?.let { key ->
        ConfirmDialog(stringResource(R.string.admin_secrets_reveal_title, key), stringResource(R.string.admin_secrets_reveal_text), stringResource(R.string.admin_secrets_show), {
            scope.attempt(snackbar) { revealed = key to api.revealEnv(key) }
        }) { revealing = null }
    }
    revealed?.let { (key, value) ->
        AlertDialog(
            onDismissRequest = { revealed = null },
            title = { Text(key) },
            text = { androidx.compose.foundation.text.selection.SelectionContainer { Text(value, fontFamily = FontFamily.Monospace) } },
            confirmButton = { TextButton(onClick = { revealed = null }) { Text(stringResource(R.string.common_hide)) } },
        )
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCatching(onError: (String) -> Unit, block: suspend () -> Unit) = launch {
    try {
        block()
    } catch (e: Exception) {
        onError(e.message ?: e.javaClass.simpleName)
    }
}

