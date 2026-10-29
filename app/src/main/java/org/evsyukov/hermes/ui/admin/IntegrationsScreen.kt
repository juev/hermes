package org.evsyukov.hermes.ui.admin

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.remote.AdminApi
import org.evsyukov.hermes.data.remote.ConnectorsApi
import org.evsyukov.hermes.data.remote.HostedAccount
import org.evsyukov.hermes.data.remote.HostedConnector
import org.evsyukov.hermes.data.remote.McpAuthFlow
import org.evsyukov.hermes.data.remote.McpCatalogEntry
import org.evsyukov.hermes.data.remote.McpServer
import org.evsyukov.hermes.data.remote.McpTest
import org.evsyukov.hermes.ui.theme.ChatTheme

/** Where the user manages the accounts connected through Nous, when the server cannot remove them itself. */
private const val PORTAL_CONNECTORS = "https://portal.nousresearch.com/connectors"

/**
 * What the agent can reach outside the server: accounts connected through Nous and MCP servers, each
 * with its state, its account or its command, the last error and what can be done with it.
 */
@Composable
fun IntegrationsScreen(api: AdminApi, connectors: ConnectorsApi, onOpen: (AdminSection) -> Unit, onBack: () -> Unit) {
    val colors = ChatTheme.colors
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = rememberSnackbar()
    val scope = rememberCoroutineScope()
    val hosted = rememberLoadable(connectors) { connectors.hosted() }
    val servers = rememberCached(api) { it.mcpServers() }
    // The server keeps no result of a test, so the screen keeps the last one of each server.
    val tests = remember { mutableStateMapOf<String, McpTest>() }
    var openConnector by remember { mutableStateOf<String?>(null) }
    var openServer by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var browsing by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf<McpCatalogEntry?>(null) }
    var action by remember { mutableStateOf<String?>(null) }
    var signIn by remember { mutableStateOf<McpAuthFlow?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    // The connection whose sign-in page is open; it outlives the activity while the browser is in front.
    var awaiting by rememberSaveable { mutableStateOf<String?>(null) }

    fun openUrl(url: String) = CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, url.toUri())

    fun test(name: String) = scope.attempt(snackbar) {
        val result = api.testMcp(name)
        tests[name] = result
        snackbar.showSnackbar(
            if (result.ok) resources.getQuantityString(R.plurals.admin_mcp_works, result.tools.size, name, result.tools.size)
            else "$name: ${result.error ?: resources.getString(R.string.admin_mcp_no_answer)}",
        )
    }

    fun connect(connector: HostedConnector, account: HostedAccount? = null) = scope.attempt(snackbar) {
        val started = connectors.connect(connector.slug, reconnect = account != null || connector.broken, connectionId = account?.connectionId)
        if (started.url != null) {
            awaiting = started.opId
            openUrl(started.url)
        } else {
            hosted.reload()
        }
    }

    // The server gives the phone no link back from the browser; coming back to the app is the sign.
    LifecycleResumeEffect(connectors) {
        awaiting?.let { opId ->
            awaiting = null
            scope.launch {
                try {
                    connectors.wake(opId)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The server checks the accounts by itself as well; the list shows what it has.
                }
                hosted.reload()
            }
        }
        onPauseOrDispose {}
    }

    // An MCP sign-in ends in the browser, on the dashboard's page; the screen asks how it went.
    LaunchedEffect(signIn?.id) {
        var flow = signIn ?: return@LaunchedEffect
        while (!flow.done) {
            delay(2_000)
            flow = try {
                api.mcpSignIn(flow)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow.copy(status = "error", error = e.message)
            }
        }
        signIn = null
        snackbar.showSnackbar(
            if (flow.status == "approved") resources.getString(R.string.integrations_signed_in, flow.server)
            else "${flow.server}: ${flow.error ?: resources.getString(R.string.error_failed)}",
        )
        servers.reload()
        if (flow.status == "approved") test(flow.server)
    }

    AdminScaffold(stringResource(R.string.admin_integrations), onBack, hosted.loading || servers.loading, { hosted.reload(); servers.reload() }, snackbar) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.integrations_hosted))
            ErrorText(hosted.error)
            hosted.value?.let { state ->
                if (!state.available) {
                    Hint(stringResource(R.string.integrations_hosted_unavailable) + (state.reason?.let { " ($it)" } ?: ""))
                } else {
                    val used = state.connectors.filter { it.connected || it.accounts.isNotEmpty() }
                    if (used.isEmpty()) Hint(stringResource(R.string.integrations_hosted_none))
                    used.forEach { connector ->
                        HostedRow(connector) { openConnector = connector.slug }
                        HorizontalDivider(color = colors.divider)
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.integrations_connect)) }
                        OutlinedButton(onClick = { openUrl(PORTAL_CONNECTORS) }) { Text(stringResource(R.string.integrations_portal)) }
                    }
                }
            }

            SectionTitle(stringResource(R.string.integrations_mcp))
            ErrorText(servers.error)
            Hint(stringResource(R.string.admin_mcp_hint))
            servers.value?.forEach { server ->
                McpRow(server, tests[server.name], onClick = { openServer = server.name }) {
                    Switch(server.enabled, { on -> scope.attempt(snackbar) { api.toggleMcp(server.name, on); servers.reload() } }, enabled = server.plugin == null)
                }
                HorizontalDivider(color = colors.divider)
            }
            OutlinedButton(onClick = { browsing = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.integrations_catalog))
            }

            SectionTitle(stringResource(R.string.integrations_more))
            ClickRow(stringResource(R.string.admin_tools), stringResource(R.string.integrations_tools_link), onClick = { onOpen(AdminSection.TOOLS) })
            Spacer(Modifier.size(32.dp))
        }
    }

    openConnector?.let { slug -> hosted.value?.connectors?.firstOrNull { it.slug == slug } }?.let { connector ->
        HostedDialog(
            connector, connectors,
            onConnect = { account -> connect(connector, account) },
            onRemove = { account ->
                confirm = resources.getString(R.string.integrations_remove_confirm, account.title(), connector.name) to {
                    scope.attempt(snackbar) { connectors.remove(account.connectionId); hosted.reload() }
                }
            },
            onPortal = { openUrl(PORTAL_CONNECTORS) },
            onDismiss = { openConnector = null },
        )
    }
    if (picking) {
        PickDialog(
            hosted.value?.connectors.orEmpty(),
            onPick = { connector -> picking = false; connect(connector) },
            onDismiss = { picking = false },
        )
    }
    openServer?.let { name -> servers.value?.firstOrNull { it.name == name } }?.let { server ->
        val name = server.name
        McpDialog(
            server, tests[name], signingIn = signIn?.server == name,
            onTest = { test(name) },
            onSignIn = {
                scope.attempt(snackbar) {
                    val flow = api.signInMcp(name)
                    if (flow.status == "error") error(flow.error ?: resources.getString(R.string.error_failed))
                    signIn = flow
                    flow.url?.let(::openUrl)
                }
            },
            onCancelSignIn = {
                signIn?.let { flow -> scope.attempt(snackbar) { api.cancelMcpSignIn(flow) } }
                signIn = null
            },
            onDismiss = { openServer = null },
        )
    }
    if (browsing) {
        CatalogDialog(api, onPick = { browsing = false; installing = it }, onDismiss = { browsing = false })
    }
    installing?.let { entry ->
        InstallDialog(entry, onDismiss = { installing = null }) { env ->
            installing = null
            scope.attempt(snackbar) {
                val result = api.installMcp(entry.name, env)
                if (result.background && result.action != null) {
                    action = result.action
                } else {
                    snackbar.showSnackbar(resources.getString(R.string.integrations_installed, entry.name))
                    servers.reload()
                }
            }
        }
    }
    action?.let { name -> ActionLog(api, name) { action = null; servers.reload() } }
    confirm?.let { (text, run) -> ConfirmDialog(stringResource(R.string.common_confirm_title), text, stringResource(R.string.integrations_remove), run) { confirm = null } }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = ChatTheme.colors.faintText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

private fun HostedAccount.title(): String = alias ?: label.ifBlank { connectionId }

/** Green when it works, red when it broke, yellow while it waits for a sign-in. */
private fun dotOf(status: String?): Boolean? = when (status) {
    "active" -> true
    "failed", "expired", "revoked", "inactive" -> false
    else -> null
}

@Composable
private fun statusText(status: String?): String = stringResource(
    when (status) {
        "active" -> R.string.integrations_status_active
        "failed" -> R.string.integrations_status_failed
        "expired" -> R.string.integrations_status_expired
        "revoked" -> R.string.integrations_status_revoked
        "inactive" -> R.string.integrations_status_inactive
        else -> R.string.integrations_status_pending
    },
)

@Composable
private fun HostedRow(connector: HostedConnector, onClick: () -> Unit) {
    val colors = ChatTheme.colors
    val accounts = connector.accounts.filterNot { it.disabled }
    StatusRow(dotOf(connector.status ?: accounts.firstOrNull()?.status), onClick) {
        Text(connector.name, color = colors.text, fontSize = 16.sp)
        Text(
            listOf(statusText(connector.status ?: accounts.firstOrNull()?.status))
                .plus(accounts.map { it.title() })
                .joinToString(" · "),
            color = colors.faintText, fontSize = 13.sp,
        )
        connector.reason?.let { Text(it, color = colors.danger, fontSize = 12.sp) }
    }
}

@Composable
private fun McpRow(server: McpServer, test: McpTest?, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    val colors = ChatTheme.colors
    StatusRow(test?.ok, onClick, trailing) {
        Text(server.name, color = colors.text, fontSize = 16.sp)
        Text(
            listOfNotNull(
                server.transport,
                server.plugin?.let { stringResource(R.string.integrations_from_plugin, it) },
                server.tools?.let { stringResource(R.string.admin_tools_count, it.size) },
            ).joinToString(" · "),
            color = colors.faintText, fontSize = 13.sp,
        )
        test?.error?.let { Text(it, color = colors.danger, fontSize = 12.sp, maxLines = 3) }
    }
}

@Composable
private fun StatusRow(ok: Boolean?, onClick: () -> Unit, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(ok)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        trailing()
    }
}

/** A long value, like a command line, under its label instead of beside it. */
@Composable
private fun Detail(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, color = ChatTheme.colors.faintText, fontSize = 12.sp)
        SelectionContainer {
            Text(value, color = ChatTheme.colors.text, fontSize = if (mono) 12.sp else 14.sp, fontFamily = if (mono) FontFamily.Monospace else null)
        }
    }
}

@Composable
private fun HostedDialog(
    connector: HostedConnector,
    api: ConnectorsApi,
    onConnect: (HostedAccount?) -> Unit,
    onRemove: (HostedAccount) -> Unit,
    onPortal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ChatTheme.colors
    val tools = rememberLoadable(connector.slug) { api.tools(connector.slug) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(connector.name) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (connector.description.isNotBlank()) Text(connector.description, color = colors.secondaryText, fontSize = 14.sp)
                connector.accounts.forEach { account ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (account.disabled) null else dotOf(account.status))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(account.title(), color = colors.text, fontSize = 15.sp)
                            Text(
                                listOfNotNull(
                                    account.label.takeIf { account.alias != null && it.isNotBlank() },
                                    if (account.disabled) stringResource(R.string.integrations_account_replaced) else statusText(account.status),
                                ).joinToString(" · "),
                                color = colors.faintText, fontSize = 13.sp,
                            )
                            account.reason?.let { Text(it, color = colors.danger, fontSize = 12.sp) }
                        }
                    }
                    if (!account.disabled) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (account.broken) TextButton(onClick = { onConnect(account) }) { Text(stringResource(R.string.integrations_reconnect)) }
                            TextButton(onClick = { onRemove(account) }) { Text(stringResource(R.string.integrations_remove), color = colors.danger) }
                        }
                    }
                }
                Text(stringResource(R.string.integrations_tools), color = colors.faintText, fontSize = 12.sp)
                ErrorText(tools.error)
                tools.value?.forEach { tool ->
                    Text(
                        "${tool.name} · ${facetText(tool.facet)}",
                        color = if (tool.facet == "destructive") colors.danger else colors.text, fontSize = 13.sp,
                    )
                    if (tool.description.isNotBlank()) Text(tool.description, color = colors.faintText, fontSize = 12.sp, maxLines = 2)
                }
                TextButton(onClick = onPortal) { Text(stringResource(R.string.integrations_portal)) }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onConnect(null) }) { Text(stringResource(R.string.integrations_connect_another)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun facetText(facet: String): String = stringResource(
    when (facet) {
        "read" -> R.string.integrations_facet_read
        "write" -> R.string.integrations_facet_write
        "destructive" -> R.string.integrations_facet_destructive
        else -> R.string.integrations_facet_unknown
    },
)

@Composable
private fun PickDialog(connectors: List<HostedConnector>, onPick: (HostedConnector) -> Unit, onDismiss: () -> Unit) {
    val colors = ChatTheme.colors
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.integrations_connect)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(query, { query = it }, placeholder = { Text(stringResource(R.string.common_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    connectors.filter { query.isBlank() || it.name.contains(query, true) || it.slug.contains(query, true) }.forEach { connector ->
                        StatusRow(if (connector.connected) true else null, onClick = { onPick(connector) }) {
                            Text(connector.name, color = colors.text, fontSize = 15.sp)
                            if (connector.description.isNotBlank()) Text(connector.description, color = colors.faintText, fontSize = 12.sp, maxLines = 2)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun McpDialog(
    server: McpServer,
    test: McpTest?,
    signingIn: Boolean,
    onTest: () -> Unit,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ChatTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(server.name) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Detail(stringResource(R.string.integrations_source), server.plugin?.let { stringResource(R.string.integrations_from_plugin, it) } ?: "config.yaml")
                Detail(stringResource(if (server.url != null) R.string.integrations_address else R.string.integrations_command), server.launch.ifBlank { "—" }, mono = true)
                Detail(
                    stringResource(R.string.integrations_auth),
                    when (server.auth) {
                        "oauth" -> "OAuth"
                        "header" -> stringResource(R.string.integrations_auth_header)
                        else -> stringResource(R.string.integrations_auth_none)
                    },
                )
                Detail(stringResource(R.string.integrations_allowed), server.tools?.joinToString(", ")?.ifEmpty { "—" } ?: stringResource(R.string.integrations_allowed_all))
                if (test != null) {
                    Detail(
                        stringResource(R.string.integrations_last_test),
                        test.error ?: if (test.ok) test.tools.joinToString(", ").ifEmpty { "—" } else stringResource(R.string.admin_mcp_no_answer),
                    )
                }
                if (signingIn) Text(stringResource(R.string.connect_waiting_browser), color = colors.secondaryText, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onTest) { Text(stringResource(R.string.integrations_test)) }
                    when {
                        signingIn -> TextButton(onClick = onCancelSignIn) { Text(stringResource(R.string.common_cancel)) }
                        server.signsIn -> TextButton(onClick = onSignIn) { Text(stringResource(R.string.integrations_sign_in)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
private fun CatalogDialog(api: AdminApi, onPick: (McpCatalogEntry) -> Unit, onDismiss: () -> Unit) {
    val colors = ChatTheme.colors
    val catalog = rememberCached(api) { it.mcpCatalog() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.integrations_catalog)) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                ErrorText(catalog.error)
                val entries = catalog.value?.filterNot { it.installed }
                if (entries?.isEmpty() == true) Text(stringResource(R.string.integrations_catalog_none), color = colors.faintText, fontSize = 14.sp)
                entries?.forEach { entry ->
                    ClickRow(entry.name, entry.description, onClick = { onPick(entry) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

/** Shows what the install clones, builds and starts, and asks for the credentials the entry declares. */
@Composable
private fun InstallDialog(entry: McpCatalogEntry, onDismiss: () -> Unit, onInstall: (Map<String, String>) -> Unit) {
    val colors = ChatTheme.colors
    val values = remember(entry.name) { mutableStateMapOf<String, String>() }
    val ready = entry.env.all { !it.required || values[it.name].orEmpty().isNotBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (entry.description.isNotBlank()) Text(entry.description, color = colors.secondaryText, fontSize = 14.sp)
                entry.source?.let { Detail(stringResource(R.string.integrations_source), it) }
                entry.installUrl?.let { Detail(stringResource(R.string.integrations_clones), listOfNotNull(it, entry.installRef).joinToString(" @ "), mono = true) }
                if (entry.bootstrap.isNotEmpty()) Detail(stringResource(R.string.integrations_builds), entry.bootstrap.joinToString("\n"), mono = true)
                Detail(stringResource(if (entry.url != null) R.string.integrations_address else R.string.integrations_command), entry.launch.ifBlank { "—" }, mono = true)
                entry.env.forEach { field ->
                    OutlinedTextField(
                        values[field.name].orEmpty(), { values[field.name] = it },
                        label = { Text(field.name + if (field.required) " *" else "") },
                        supportingText = { Text(field.prompt) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (entry.postInstall.isNotBlank()) Text(entry.postInstall, color = colors.faintText, fontSize = 12.sp)
            }
        },
        confirmButton = {
            TextButton(enabled = ready, onClick = { onInstall(values.filterValues { it.isNotBlank() }) }) { Text(stringResource(R.string.integrations_install)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
