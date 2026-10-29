package org.evsyukov.hermes.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.evsyukov.hermes.R
import org.evsyukov.hermes.chat.ConnectionOp
import org.evsyukov.hermes.chat.ConnectionTarget
import org.evsyukov.hermes.chat.ConnectorApp
import org.evsyukov.hermes.chat.EnvField
import org.evsyukov.hermes.chat.TargetAnswer
import org.evsyukov.hermes.chat.TargetState
import org.evsyukov.hermes.ui.theme.ChatTheme

/** What a connection card can ask of the chat. */
class ConnectionActions(
    val answer: (opId: String, List<TargetAnswer>) -> Unit,
    val proceed: (opId: String) -> Unit,
    val retry: (opId: String, name: String) -> Unit,
    val reinstall: (opId: String, name: String) -> Unit,
    /** Opens a sign-in link in the browser; the card marks the row as waiting for it. */
    val openLink: (opId: String, name: String, url: String) -> Unit,
    val directory: suspend () -> List<ConnectorApp>,
    /** Starts connecting an app of the directory and gives its sign-in link. */
    val connectApp: suspend (ConnectorApp) -> String?,
    val openUrl: (String) -> Unit,
)

/**
 * The card of a connection operation: accounts to connect, MCP servers, catalog plugins and skills
 * to install, or a directory of apps. Every row shows what the server last said about it; a button
 * answers the server, and the row changes once the server reports the change.
 */
@Composable
fun ConnectionCard(connection: ConnectionOp, actions: ConnectionActions) {
    val colors = ChatTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, colors.composerBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val suggested = connection.suggested
        if (suggested != null) {
            DirectoryCard(connection, suggested, actions)
            return@Column
        }
        Text(cardTitle(connection), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        connection.targets.forEach { target ->
            when {
                target.catalog != null -> CatalogRow(connection, target, actions)
                target.kind == "connector" -> ConnectorRow(connection, target, actions)
                else -> McpRow(connection, target, actions)
            }
        }
        if (!connection.settled && connection.targets.any { !resolved(it) }) {
            OutlinedButton(onClick = { actions.proceed(connection.opId) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.connect_continue), color = colors.text)
            }
        }
    }
}

@Composable
private fun cardTitle(connection: ConnectionOp): String = when {
    connection.catalog -> stringResource(R.string.connect_title_catalog)
    connection.targets.all { it.kind == "connector" } -> stringResource(R.string.connect_title_accounts)
    else -> when (connection.targets.first().action) {
        "enable" -> stringResource(R.string.connect_title_mcp_enable)
        "authorize" -> stringResource(R.string.connect_title_mcp_authorize)
        else -> stringResource(R.string.connect_title_mcp_install)
    }
}

/** The row needs nothing more from the user: connected or skipped, or the server says it is done. */
private fun resolved(target: ConnectionTarget): Boolean = when {
    target.catalog != null -> target.resolved
    else -> target.state == TargetState.CONNECTED || target.state == TargetState.SKIPPED
}

/** `google_drive` reads as "Google drive". */
private fun appName(slug: String): String = slug.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }

@Composable
private fun RowHead(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String?, done: Boolean) {
    val colors = ChatTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Outlined.CheckCircle else icon, null, tint = if (done) colors.success else colors.secondaryText, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.secondaryText, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun Waiting(text: String) {
    val colors = ChatTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = colors.secondaryText)
        Spacer(Modifier.width(8.dp))
        Text(text, color = colors.secondaryText, fontSize = 13.sp)
    }
}

@Composable
private fun RowButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(text, color = ChatTheme.colors.text) }
}

@Composable
private fun stateWord(state: TargetState): String = when (state) {
    TargetState.CONNECTED -> stringResource(R.string.connect_state_connected)
    TargetState.SKIPPED -> stringResource(R.string.connect_state_skipped)
    else -> stringResource(R.string.connect_state_not_connected)
}

/** An account connected through Nous: open the sign-in, try again when it failed or expired. */
@Composable
private fun ConnectorRow(connection: ConnectionOp, target: ConnectionTarget, actions: ConnectionActions) {
    val app = appName(target.name)
    val title = target.alias?.let { "$app · $it" } ?: app
    val waiting = !connection.settled && target.state == TargetState.INITIATED && target.name in connection.opened
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RowHead(Icons.Outlined.Link, title, if (waiting) null else stateWord(target.state), done = target.state == TargetState.CONNECTED)
        if (waiting) Waiting(stringResource(R.string.connect_waiting_browser))
        if (!connection.settled) {
            when (target.state) {
                TargetState.PENDING, TargetState.INITIATED -> {
                    val url = target.connectUrl
                    RowButton(stringResource(R.string.connect_connect), enabled = url != null) { url?.let { actions.openLink(connection.opId, target.name, it) } }
                }
                TargetState.FAILED, TargetState.EXPIRED -> RowButton(stringResource(R.string.connect_try_again)) { actions.retry(connection.opId, target.name) }
                else -> Unit
            }
        }
        if (target.state == TargetState.CONNECTED && target.alias != null) {
            Text(stringResource(R.string.connect_named_account, app, target.alias), color = ChatTheme.colors.faintText, fontSize = 13.sp)
        }
    }
}

/** An MCP server to install, enable or authorize, with the credentials it asks for. */
@Composable
private fun McpRow(connection: ConnectionOp, target: ConnectionTarget, actions: ConnectionActions) {
    val colors = ChatTheme.colors
    var setup by remember { mutableStateOf(false) }
    val verb = when (target.action) {
        "enable" -> stringResource(R.string.connect_mcp_enable)
        "authorize" -> stringResource(R.string.connect_mcp_authorize)
        else -> stringResource(R.string.connect_mcp_install)
    }
    val done = when (target.action) {
        "enable" -> stringResource(R.string.connect_mcp_enabled, target.name)
        "authorize" -> stringResource(R.string.connect_mcp_authorized, target.name)
        else -> stringResource(R.string.connect_mcp_installed, target.name)
    }
    val sub = when (target.state) {
        TargetState.CONNECTED -> listOfNotNull(
            done,
            if (target.discoveryError != null) stringResource(R.string.connect_tools_unavailable)
            else target.tools.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.connect_tools, it, it) },
        ).joinToString(" · ")
        else -> stateWord(target.state)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RowHead(Icons.Outlined.Extension, target.name, sub, done = target.state == TargetState.CONNECTED)
        target.instructions?.takeIf { it.isNotBlank() && target.state != TargetState.CONNECTED }?.let { Text(it, color = colors.secondaryText, fontSize = 13.sp) }
        if (!connection.settled) {
            val url = target.connectUrl
            when {
                target.state == TargetState.PENDING && target.action != "authorize" -> RowButton(verb) {
                    if (target.requiredEnv.isEmpty()) actions.answer(connection.opId, listOf(TargetAnswer(target.name, approved = true))) else setup = true
                }
                target.state == TargetState.INITIATED && url != null -> {
                    if (target.name in connection.opened) Waiting(stringResource(R.string.connect_waiting_browser))
                    RowButton(if (target.action == "authorize") verb else stringResource(R.string.connect_open_browser)) { actions.openLink(connection.opId, target.name, url) }
                }
                target.state == TargetState.INITIATED || target.state == TargetState.PENDING -> Waiting(verb)
                target.state == TargetState.FAILED || target.state == TargetState.EXPIRED -> {
                    target.detail.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.danger, fontSize = 13.sp) }
                    RowButton(stringResource(R.string.connect_try_again)) { actions.retry(connection.opId, target.name) }
                }
            }
        }
    }
    if (setup) {
        EnvDialog(
            title = stringResource(R.string.connect_setup_title, target.name),
            note = target.instructions,
            fields = target.requiredEnv,
            confirm = stringResource(R.string.connect_connect),
            onDismiss = {
                setup = false
                actions.answer(connection.opId, listOf(TargetAnswer(target.name, approved = false)))
            },
            onConfirm = { env ->
                setup = false
                actions.answer(connection.opId, listOf(TargetAnswer(target.name, approved = true, env = env)))
            },
        )
    }
}

/** The credentials an install asks for; Cancel skips the row. */
@Composable
private fun EnvDialog(title: String, note: String?, fields: List<EnvField>, confirm: String, onDismiss: () -> Unit, onConfirm: (Map<String, String>) -> Unit) {
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { if (!it.secret) put(it.name, it.default) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                note?.takeIf { it.isNotBlank() }?.let { Text(it) }
                EnvFields(fields, values)
            }
        },
        confirmButton = {
            TextButton(enabled = fields.none { it.required && values[it.name].isNullOrBlank() }, onClick = { onConfirm(values.filterValues { it.isNotEmpty() }) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun EnvFields(fields: List<EnvField>, values: MutableMap<String, String>) {
    fields.forEach { field ->
        OutlinedTextField(
            values[field.name].orEmpty(), { values[field.name] = it },
            label = { Text(field.name) },
            placeholder = field.prompt?.let { { Text(it) } },
            supportingText = if (field.required) { { Text(stringResource(R.string.connect_required)) } } else null,
            singleLine = true,
            visualTransformation = if (field.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A plugin or a skill of the catalog: what it is, where it comes from, and how its install went. */
@Composable
private fun CatalogRow(connection: ConnectionOp, target: ConnectionTarget, actions: ConnectionActions) {
    val colors = ChatTheme.colors
    val catalog = target.catalog ?: return
    val plugin = target.kind == "plugin"
    var advanced by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RowHead(if (plugin) Icons.Outlined.Extension else Icons.AutoMirrored.Outlined.MenuBook, catalog.display, catalog.description, done = target.state == TargetState.CONNECTED)
        val pills = listOfNotNull(
            stringResource(if (plugin) R.string.connect_kind_plugin else R.string.connect_kind_skill),
            catalog.tier?.let { tierWord(it) },
        ) + catalog.platforms.map(::platformName)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { pills.forEach { Pill(it) } }
        catalog.targetProfile?.let { Text(stringResource(R.string.connect_into_profile, it), color = colors.faintText, fontSize = 12.sp) }
        when {
            target.state == TargetState.CONNECTED -> {
                val parts = listOfNotNull(
                    stringResource(R.string.connect_installed),
                    target.tools.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.connect_tools, it, it) },
                    catalog.skill?.let { stringResource(R.string.connect_skill_name, it) },
                )
                Text(parts.joinToString(" · "), color = colors.success, fontSize = 13.sp)
                if (target.tools.isNotEmpty()) ToolNames(target.tools)
                catalog.serverErrors.forEach { (name, error) -> Note(stringResource(R.string.connect_server_error, name, error)) }
                if (catalog.enabled == false) Note(stringResource(R.string.connect_not_enabled))
                if (catalog.missingEnv.isNotEmpty()) Note(stringResource(R.string.connect_missing_env, catalog.missingEnv.joinToString(", ")))
                if (catalog.alreadyInstalled) Note(stringResource(R.string.connect_already_installed))
            }
            target.state == TargetState.FAILED || target.state == TargetState.EXPIRED -> {
                Text(listOf(stringResource(R.string.connect_failed), target.detail).filter { it.isNotBlank() }.joinToString(" · "), color = colors.danger, fontSize = 13.sp)
                when {
                    !connection.settled -> RowButton(stringResource(R.string.connect_try_again)) { actions.answer(connection.opId, listOf(TargetAnswer(target.name, approved = true))) }
                    plugin -> RowButton(stringResource(R.string.connect_try_again)) { actions.reinstall(connection.opId, target.name) }
                }
            }
            target.state == TargetState.SKIPPED -> Text(stringResource(R.string.connect_state_skipped), color = colors.secondaryText, fontSize = 13.sp)
            connection.settled -> Text(stringResource(R.string.connect_not_installed), color = colors.secondaryText, fontSize = 13.sp)
            target.state == TargetState.INITIATED -> {
                Text(phaseWord(catalog.phase), color = colors.secondaryText, fontSize = 13.sp)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.answer(connection.opId, listOf(TargetAnswer(target.name, approved = true))) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.connect_install), color = colors.text)
                }
                OutlinedButton(onClick = { advanced = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.connect_advanced), color = colors.text, maxLines = 1) }
                TextButton(onClick = { actions.answer(connection.opId, listOf(TargetAnswer(target.name, approved = false))) }) { Text(stringResource(R.string.connect_skip), color = colors.secondaryText) }
            }
        }
    }
    if (advanced) {
        AdvancedDialog(target, actions, onDismiss = { advanced = false }) { env ->
            advanced = false
            actions.answer(connection.opId, listOf(TargetAnswer(target.name, approved = true, env = env)))
        }
    }
}

@Composable
private fun Pill(text: String) {
    val colors = ChatTheme.colors
    Text(text, color = colors.secondaryText, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.userBubble).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
private fun Note(text: String) = Text(text, color = ChatTheme.colors.warning, fontSize = 13.sp)

@Composable
private fun ToolNames(tools: List<String>) {
    var shown by remember { mutableStateOf(false) }
    Text(
        stringResource(if (shown) R.string.connect_hide_names else R.string.connect_show_names),
        color = ChatTheme.colors.secondaryText, fontSize = 13.sp, modifier = Modifier.clickable { shown = !shown },
    )
    if (shown) Text(tools.joinToString(", "), color = ChatTheme.colors.faintText, fontSize = 12.sp)
}

@Composable
private fun tierWord(tier: String): String = when (tier) {
    "official" -> stringResource(R.string.connect_tier_official)
    "verified" -> stringResource(R.string.connect_tier_verified)
    "community" -> stringResource(R.string.connect_tier_community)
    else -> tier
}

private fun platformName(platform: String): String = when (platform.lowercase()) {
    "darwin", "macos" -> "macOS"
    "linux" -> "Linux"
    "windows", "win32" -> "Windows"
    else -> platform
}

@Composable
private fun phaseWord(phase: String?): String = when (phase) {
    "downloading" -> stringResource(R.string.connect_phase_downloading)
    "python_packages" -> stringResource(R.string.connect_phase_packages)
    "loading_tools" -> stringResource(R.string.connect_phase_tools)
    else -> stringResource(R.string.connect_installing)
}

private val COMMIT_SHA = Regex("^[0-9a-fA-F]{40}$")

/**
 * The details of a catalog install and its choices: where it comes from, the scan, what it needs, the
 * credentials, and for a plugin which halves to install, whether to turn it on and the commit to pin.
 */
@Composable
private fun AdvancedDialog(target: ConnectionTarget, actions: ConnectionActions, onDismiss: () -> Unit, onInstall: (Map<String, String>) -> Unit) {
    val catalog = target.catalog ?: return
    val plugin = target.kind == "plugin"
    val colors = ChatTheme.colors
    val values = remember { mutableStateMapOf<String, String>().apply { target.requiredEnv.forEach { if (!it.secret) put(it.name, it.default) } } }
    var agentHalf by remember { mutableStateOf(true) }
    var desktopHalf by remember { mutableStateOf(catalog.hasDesktopHalf) }
    var enable by remember { mutableStateOf(true) }
    var force by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf(catalog.sha.orEmpty()) }
    val pinOk = pin.isEmpty() || COMMIT_SHA.matches(pin)
    val ready = pinOk && (!plugin || agentHalf || desktopHalf) && target.requiredEnv.none { it.required && values[it.name].isNullOrBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(catalog.display) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                catalog.repo?.let { repo ->
                    Detail(stringResource(R.string.connect_repository), repo)
                    if (repo.startsWith("https://") || !repo.contains("://")) {
                        val url = if (repo.contains("://")) repo else "https://github.com/$repo"
                        Text(stringResource(R.string.connect_view_repository), color = colors.text, fontSize = 13.sp, modifier = Modifier.clickable { actions.openUrl(url) })
                    }
                }
                catalog.sha?.let { Detail(stringResource(R.string.connect_commit), it.take(12)) }
                catalog.subdir?.let { Detail(stringResource(R.string.connect_folder), it) }
                catalog.scan?.let { (status, summary) ->
                    val word = when (status) {
                        "passed" -> stringResource(R.string.connect_scan_passed)
                        "warnings" -> stringResource(R.string.connect_scan_warnings)
                        else -> stringResource(R.string.connect_scan_failed)
                    }
                    Text(listOf(word, summary).filter { it.isNotBlank() }.joinToString(" · "), color = if (status == "passed") colors.success else colors.warning, fontSize = 13.sp)
                }
                val requires = listOfNotNull(catalog.requiresHermes?.let { "Hermes $it" }) +
                    target.requiredEnv.map { stringResource(R.string.connect_env_var, it.name) }
                if (requires.isNotEmpty()) Detail(stringResource(R.string.connect_requires), requires.joinToString(", "))
                EnvFields(target.requiredEnv, values)
                if (plugin) {
                    Toggle(stringResource(R.string.connect_agent_half), agentHalf) { agentHalf = it }
                    if (catalog.hasDesktopHalf) Toggle(stringResource(R.string.connect_desktop_half), desktopHalf) { desktopHalf = it }
                    Toggle(stringResource(R.string.connect_turn_on), enable) { enable = it }
                }
                Toggle(stringResource(R.string.connect_force), force) { force = it }
                if (plugin) {
                    OutlinedTextField(
                        pin, { pin = it.trim() },
                        label = { Text(stringResource(R.string.connect_pin)) },
                        isError = !pinOk,
                        supportingText = if (!pinOk) { { Text(stringResource(R.string.connect_pin_invalid)) } } else null,
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = {
                val env = values.filterValues { it.isNotEmpty() }.toMutableMap()
                env["force"] = if (force) "1" else "0"
                catalog.targetProfile?.let { env["target_profile"] = it }
                if (plugin) {
                    env["agent_half"] = if (agentHalf) "1" else "0"
                    env["enable"] = if (enable) "1" else "0"
                    if (catalog.hasDesktopHalf) env["desktop_half"] = if (desktopHalf) "1" else "0"
                    if (pin.isNotEmpty()) env["ref"] = pin
                }
                onInstall(env)
            }) { Text(stringResource(R.string.connect_install)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun Detail(label: String, value: String) {
    val colors = ChatTheme.colors
    Column {
        Text(label, color = colors.faintText, fontSize = 12.sp)
        Text(value, color = colors.text, fontSize = 14.sp)
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ChatTheme.colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(value, onChange)
    }
}

private const val SEARCH_FROM = 12

/** The apps the user can connect, suggested ones first; a tap opens the sign-in of the app. */
@Composable
private fun DirectoryCard(connection: ConnectionOp, suggested: List<String>, actions: ConnectionActions) {
    val colors = ChatTheme.colors
    val scope = rememberCoroutineScope()
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    val failed = remember { mutableStateMapOf<String, Boolean>() }
    var query by remember { mutableStateOf("") }
    val apps by produceState<Result<List<ConnectorApp>>?>(null, connection.opId) { value = runCatching { actions.directory() } }
    Text(stringResource(R.string.connect_title_directory), color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    if (connection.settled) {
        Text(stringResource(R.string.connect_directory_closed), color = colors.secondaryText, fontSize = 13.sp)
        return
    }
    val result = apps
    when {
        result == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        result.isFailure -> Text(result.exceptionOrNull()?.message.orEmpty(), color = colors.danger, fontSize = 13.sp)
        else -> {
            val all = result.getOrThrow()
            val bySlug = all.associateBy { it.slug }
            val first = suggested.mapNotNull { bySlug[it] ?: ConnectorApp(it, connected = false, status = null) }
            val rest = all.filter { it.slug !in suggested }
            if (all.size > SEARCH_FROM) {
                OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.connect_find_app)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            fun matches(app: ConnectorApp) = query.isBlank() || appName(app.slug).contains(query.trim(), ignoreCase = true)
            val sections = listOf(R.string.connect_suggested to first.filter(::matches), R.string.connect_all_apps to rest.filter(::matches)).filter { it.second.isNotEmpty() }
            if (sections.isEmpty()) Text(stringResource(R.string.connect_no_apps), color = colors.secondaryText, fontSize = 13.sp)
            sections.forEach { (title, list) ->
                Text(stringResource(title), color = colors.faintText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                list.forEach { app ->
                    val sub = when {
                        busy[app.slug] == true -> stringResource(R.string.connect_opening)
                        failed[app.slug] == true -> stringResource(R.string.connect_tap_retry)
                        app.connected -> stringResource(R.string.connect_state_connected)
                        else -> stringResource(R.string.connect_state_not_connected)
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = busy[app.slug] != true) {
                            busy[app.slug] = true
                            failed.remove(app.slug)
                            scope.launch {
                                try {
                                    actions.connectApp(app)?.let(actions.openUrl)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    failed[app.slug] = true
                                } finally {
                                    busy.remove(app.slug)
                                }
                            }
                        }.padding(vertical = 6.dp),
                    ) { RowHead(Icons.Outlined.Link, appName(app.slug), sub, done = app.connected) }
                }
            }
        }
    }
    OutlinedButton(onClick = { actions.proceed(connection.opId) }, enabled = apps != null, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.connect_continue), color = colors.text)
    }
}
