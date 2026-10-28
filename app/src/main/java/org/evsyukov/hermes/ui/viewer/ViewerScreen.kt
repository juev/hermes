package org.evsyukov.hermes.ui.viewer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import org.evsyukov.hermes.ui.common.MarkdownText
import org.evsyukov.hermes.ui.theme.ChatTheme
import java.io.File

/** A file to show: the remote one is downloaded by [load] into the cache before it opens. */
data class ViewerFile(val name: String, val mime: String?, val load: suspend () -> File)

/** Opens an attachment inside the app: pictures, PDF, Markdown, text and code, audio and video. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(target: ViewerFile, onBack: () -> Unit) {
    val context = LocalContext.current
    val kind = remember(target) { viewerKind(target.name, target.mime) }
    var failure by remember(target) { mutableStateOf<String?>(null) }
    val file by produceState<File?>(null, target) {
        value = try {
            target.load()
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
            null
        }
    }
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(target.mime ?: "application/octet-stream")) { uri ->
        val source = file
        if (uri != null && source != null) scope.launch(Dispatchers.IO) { copyTo(context, source, uri) }
    }

    Scaffold(
        containerColor = ChatTheme.colors.background,
        topBar = {
            TopAppBar(
                title = { Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
                actions = {
                    val ready = file
                    if (ready != null) {
                        IconButton(onClick = { save.launch(target.name) }) { Icon(Icons.Filled.Download, contentDescription = "Сохранить") }
                        IconButton(onClick = { share(context, ready, target.mime) }) { Icon(Icons.Filled.Share, contentDescription = "Поделиться") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ChatTheme.colors.background),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            val ready = file
            when {
                failure != null -> Text("Файл не загрузился: $failure", color = ChatTheme.colors.secondaryText, modifier = Modifier.padding(24.dp))
                ready == null -> CircularProgressIndicator()
                kind == ViewerKind.IMAGE -> ZoomableAsyncImage(
                    model = ready,
                    contentDescription = target.name,
                    modifier = Modifier.fillMaxSize(),
                )
                kind == ViewerKind.PDF -> PdfPages(ready)
                kind == ViewerKind.MARKDOWN -> TextFile(ready) { text ->
                    SelectionContainer {
                        MarkdownText(text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp))
                    }
                }
                kind == ViewerKind.TEXT -> TextFile(ready) { text -> CodeText(text, codeLanguage(target.name)) }
                kind == ViewerKind.AUDIO || kind == ViewerKind.VIDEO -> MediaPlayerView(ready)
                else -> OtherFile(target, ready)
            }
        }
    }
}

/** Large text files are cut, so a log of many megabytes still opens. */
private const val TEXT_LIMIT = 512 * 1024

@Composable
private fun TextFile(file: File, content: @Composable (String) -> Unit) {
    val text by produceState<String?>(null, file) {
        value = withContext(Dispatchers.IO) {
            file.inputStream().use { input ->
                val bytes = input.readNBytes(TEXT_LIMIT + 1)
                val body = String(bytes, 0, minOf(bytes.size, TEXT_LIMIT), Charsets.UTF_8)
                if (bytes.size > TEXT_LIMIT) "$body\n\n… файл обрезан до 512 КБ" else body
            }
        }
    }
    val loaded = text
    if (loaded == null) CircularProgressIndicator() else content(loaded)
}

/** Code is highlighted through a fenced block while it is small enough for the highlighter. */
@Composable
private fun CodeText(text: String, language: String?) {
    if (language != null && text.length < 64 * 1024) {
        SelectionContainer {
            MarkdownText(
                "```$language\n$text\n```",
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            )
        }
        return
    }
    SelectionContainer {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = ChatTheme.colors.text,
            modifier = Modifier
                .fillMaxSize()
                .background(ChatTheme.colors.codeBackground)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        )
    }
}

@Composable
private fun MediaPlayerView(file: File) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            VideoView(context).apply {
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoURI(Uri.fromFile(file))
                setOnPreparedListener { start(); controller.show(0) }
            }
        },
        onRelease = { it.stopPlayback() },
    )
}

@Composable
private fun OtherFile(target: ViewerFile, file: File) {
    val context = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.InsertDriveFile, contentDescription = null, tint = ChatTheme.colors.secondaryText)
        Text(target.name, color = ChatTheme.colors.text)
        Text("${file.length() / 1024} КБ · ${target.mime ?: "тип неизвестен"}", color = ChatTheme.colors.secondaryText)
        Text("Этот формат приложение не показывает само.", color = ChatTheme.colors.faintText)
        TextButton(onClick = { share(context, file, target.mime, view = true) }) { Text("Открыть в другом приложении") }
    }
}

private fun contentUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", file)

private fun share(context: Context, file: File, mime: String?, view: Boolean = false) {
    val uri = contentUri(context, file)
    val intent = if (view) {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*")
    } else {
        Intent(Intent.ACTION_SEND).setType(mime ?: "*/*").putExtra(Intent.EXTRA_STREAM, uri)
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, file.name))
    } catch (_: ActivityNotFoundException) {
        // Nothing on the phone takes this type; the file stays in the app.
    }
}

private fun copyTo(context: Context, file: File, uri: Uri) {
    context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
}
