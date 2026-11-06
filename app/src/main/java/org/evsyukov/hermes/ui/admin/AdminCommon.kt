package org.evsyukov.hermes.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.remote.ActionStatus
import org.evsyukov.hermes.data.remote.AdminApi
import org.evsyukov.hermes.data.remote.HermesException
import org.evsyukov.hermes.ui.theme.ChatTheme
import java.io.IOException

/** The data a section shows, loaded by [load] and reloaded with [reload]. */
class Loadable<T>(val value: T?, val error: String?, val loading: Boolean, val reload: () -> Unit)

@Composable
fun <T> rememberLoadable(vararg keys: Any?, load: suspend () -> T): Loadable<T> = loadable(keys, cached = null, load = load)

/**
 * Like [rememberLoadable], but while the server is asked the section shows the answer the
 * phone keeps: [read] runs on the cached copy of [api] first, then on [api] itself.
 */
@Composable
fun <T> rememberCached(api: AdminApi, vararg keys: Any?, read: suspend (AdminApi) -> T): Loadable<T> =
    loadable(keys, cached = api.cached?.let { cached -> { read(cached) } }, load = { read(api) })

@Composable
private fun <T> loadable(keys: Array<out Any?>, cached: (suspend () -> T)?, load: suspend () -> T): Loadable<T> {
    var value by remember(*keys) { mutableStateOf<T?>(null) }
    var error by remember(*keys) { mutableStateOf<String?>(null) }
    var loading by remember(*keys) { mutableStateOf(true) }
    var tick by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(tick, *keys) {
        loading = true
        if (value == null && cached != null) {
            try {
                value = cached()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not on the phone: the section waits for the server.
            }
        }
        try {
            value = load()
            error = null
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        loading = false
    }
    return Loadable(value, error, loading) { tick++ }
}

/** The frame of every section: a back arrow, a refresh button, a progress line and a snackbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScaffold(
    title: String,
    onBack: () -> Unit,
    loading: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    snackbar: SnackbarHostState? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = ChatTheme.colors
    Scaffold(
        containerColor = colors.background,
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        topBar = {
            TopAppBar(
                title = { Text(title, fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    actions()
                    if (onRefresh != null) IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, stringResource(R.string.admin_refresh)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
fun ErrorText(error: String?) {
    if (error != null) Text(error, color = ChatTheme.colors.dangerText, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = ChatTheme.colors.faintText, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp))
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(ChatTheme.colors.sidebar).padding(14.dp),
        content = content,
    )
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = ChatTheme.colors.text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, color = ChatTheme.colors.secondaryText, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 14.sp)
    }
}

@Composable
fun StatusDot(ok: Boolean?) {
    val color = when (ok) {
        true -> ChatTheme.colors.success
        false -> ChatTheme.colors.danger
        null -> ChatTheme.colors.warning
    }
    Box(Modifier.size(9.dp).clip(CircleShape).background(color))
}

/** Asks before a step that breaks something running or cannot be taken back. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = ChatTheme.colors.dangerText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Follows a background command of the dashboard until it ends, showing its output. */
@Composable
fun ActionLog(api: AdminApi, name: String, onDone: () -> Unit) {
    var status by remember(name) { mutableStateOf<ActionStatus?>(null) }
    LaunchedEffect(name) {
        while (true) {
            try {
                status = api.action(name)
            } catch (_: Exception) {
                // The gateway restarting can take the dashboard down for a moment.
            }
            if (status?.running == false) break
            delay(1_500)
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status?.running != false) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(name)
            }
        },
        text = {
            SelectionContainer {
                Text(
                    status?.lines?.joinToString("\n").orEmpty().ifEmpty { "…" },
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDone) {
                Text(if (status?.running == false) stringResource(R.string.admin_done, status?.exitCode ?: 0) else stringResource(R.string.common_hide))
            }
        },
    )
}

/** Runs [block] and reports a failure in the snackbar. */
fun CoroutineScope.attempt(snackbar: SnackbarHostState, done: String? = null, block: suspend () -> Unit) = launch {
    try {
        block()
        if (done != null) snackbar.showSnackbar(done)
    } catch (e: Exception) {
        snackbar.showSnackbar(e.message ?: e.javaClass.simpleName)
    }
}

@Composable
fun rememberSnackbar() = remember { SnackbarHostState() }

/**
 * Where a form that writes to the server is shown: an [AlertDialog] in the app. The Robolectric and
 * Compose harness pinned in this project's JVM tests did not settle an editable text field inside a
 * Compose dialog, so those tests host the same content in the screen instead. Production keeps the
 * dialog wrapper.
 */
internal interface FormWindow {
    @Composable
    fun Show(
        title: @Composable () -> Unit,
        onDismiss: () -> Unit,
        confirm: @Composable () -> Unit,
        dismiss: @Composable () -> Unit,
        content: @Composable () -> Unit,
    )
}

private object DialogFormWindow : FormWindow {
    @Composable
    override fun Show(
        title: @Composable () -> Unit,
        onDismiss: () -> Unit,
        confirm: @Composable () -> Unit,
        dismiss: @Composable () -> Unit,
        content: @Composable () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = title,
            text = content,
            confirmButton = confirm,
            dismissButton = dismiss,
        )
    }
}

internal val LocalFormWindow = staticCompositionLocalOf<FormWindow> { DialogFormWindow }

/**
 * The state of a form that writes to the server: one submission at a time and the error kept in
 * the form itself. The form stays open on failure; the caller closes it in [onSuccess] only.
 */
class FormSubmission {
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun clearError() {
        error = null
    }

    /**
     * Sends [block] once. An HTTP answer is an explicit rejection and shows what the server said.
     * A lost connection is not: for a non-idempotent write the caller passes [unknownOutcome], which
     * says to inspect the state before trying again; nothing is re-sent by itself.
     */
    fun submit(
        scope: CoroutineScope,
        connectionLost: String,
        unknownOutcome: String? = null,
        block: suspend () -> Unit,
        onSuccess: () -> Unit,
    ) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try {
                block()
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: HermesException) {
                error = e.detail.ifBlank { e.message ?: e.javaClass.simpleName }
            } catch (e: IOException) {
                error = unknownOutcome ?: connectionLost
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                saving = false
            }
        }
    }
}

@Composable
fun rememberFormSubmission(vararg keys: Any?): FormSubmission = remember(*keys) { FormSubmission() }

@Composable
fun ClickRow(text: String, sub: String? = null, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text, color = ChatTheme.colors.text, fontSize = 16.sp)
            if (!sub.isNullOrBlank()) Text(sub, color = ChatTheme.colors.faintText, fontSize = 13.sp, maxLines = 4)
        }
        trailing()
    }
}
