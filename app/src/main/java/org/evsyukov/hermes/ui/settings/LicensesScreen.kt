package org.evsyukov.hermes.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.evsyukov.hermes.R
import org.evsyukov.hermes.ui.admin.AdminScaffold
import org.evsyukov.hermes.ui.admin.ClickRow
import org.evsyukov.hermes.ui.admin.ErrorText
import org.evsyukov.hermes.ui.admin.SectionTitle
import org.evsyukov.hermes.ui.admin.rememberLoadable
import org.evsyukov.hermes.ui.theme.ChatTheme

/** The license of the app and of everything it is built from; a row opens the full text. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val licenses = rememberLoadable { withContext(Dispatchers.IO) { loadLicenses(context) } }
    // The text being read and the name over it; back returns to the list.
    var open by remember { mutableStateOf<Pair<String, List<LicenseText>>?>(null) }
    BackHandler(enabled = open != null) { open = null }

    open?.let { (title, texts) ->
        LicenseTextScreen(title, texts, onBack = { open = null })
        return
    }

    val appName = stringResource(R.string.licenses_app)
    AdminScaffold(stringResource(R.string.licenses_title), onBack, loading = licenses.loading) {
        ErrorText(licenses.error)
        val loaded = licenses.value ?: return@AdminScaffold
        LazyColumn(Modifier.fillMaxSize().testTag(LICENSES_LIST)) {
            item { SectionTitle(stringResource(R.string.licenses_this_app)) }
            item { ClickRow(appName, loaded.app.name, onClick = { open = appName to listOf(loaded.app) }) }
            item { SectionTitle(stringResource(R.string.licenses_libraries, loaded.libraries.size)) }
            items(loaded.libraries) { component -> ComponentRow(component) { open = it } }
            if (loaded.tailnet.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.licenses_tailnet, loaded.tailnet.size)) }
                items(loaded.tailnet) { component -> ComponentRow(component) { open = it } }
            }
        }
    }
}

@Composable
private fun ComponentRow(component: Component, onOpen: (Pair<String, List<LicenseText>>) -> Unit) {
    val sub = listOf(component.version, component.licenses.joinToString { it.name }).filter { it.isNotBlank() }.joinToString(" · ")
    ClickRow(component.name, sub, onClick = { onOpen(component.name to component.licenses) })
}

/** The texts cut at empty lines, so a long license is laid out only as far as it is scrolled. */
@Composable
private fun LicenseTextScreen(title: String, texts: List<LicenseText>, onBack: () -> Unit) {
    val paragraphs = remember(texts) { texts.flatMap { paragraphs(it.text) } }
    AdminScaffold(title, onBack) {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize()) {
                items(paragraphs) { paragraph ->
                    Text(
                        paragraph, color = ChatTheme.colors.text, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * A text split where it has an empty line, without the form feeds some old licenses carry. The
 * lines of a paragraph are wrapped for a wide terminal, so they are joined again; an item of a
 * list still starts a line of its own.
 */
fun paragraphs(text: String): List<String> =
    text.replace("\r\n", "\n").replace("\u000c", "").split(Regex("\n[ \t]*\n")).filter { it.isNotBlank() }.map { paragraph ->
        paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }.fold("") { joined, line ->
            when {
                joined.isEmpty() -> line
                LIST_ITEM.containsMatchIn(line) -> "$joined\n$line"
                else -> "$joined $line"
            }
        }
    }

/** The start of an item of a list: `*`, `-`, `1.`, `(a)`, `a)`. */
private val LIST_ITEM = Regex("""^(\*|-|•|\d{1,2}\.|\(\w{1,4}\)|[a-z]\))\s""")

/** The list of the screen, for tests to scroll. */
const val LICENSES_LIST = "licenses-list"
