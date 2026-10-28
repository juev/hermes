package org.evsyukov.hermes.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.hermes.chat.LocalAttachment
import org.evsyukov.hermes.ui.theme.ChatTheme

/** Attachments over this size are refused: the gateway takes at most 25 MiB per picture. */
private const val MAX_ATTACHMENT = 25L * 1024 * 1024

@Composable
fun Composer(
    text: String,
    onText: (String) -> Unit,
    attachments: List<LocalAttachment>,
    onAttachments: (List<LocalAttachment>) -> Unit,
    running: Boolean,
    enterSends: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    transcribe: suspend (ByteArray, String) -> String,
    onError: (String) -> Unit,
) {
    val colors = ChatTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    var recorder by remember { mutableStateOf<Recording?>(null) }
    var transcribing by remember { mutableStateOf(false) }

    fun add(uris: List<Uri>) {
        scope.launch {
            val picked = withContext(Dispatchers.IO) { uris.mapNotNull { read(context, it) } }
            if (picked.size < uris.size) onError("Файлы больше 25 МБ не прикрепляются")
            onAttachments(attachments + picked)
        }
    }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { add(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraFile
        if (ok && file != null) onAttachments(attachments + LocalAttachment("photo_${System.currentTimeMillis()}.jpg", "image/jpeg", file.readBytes()))
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recorder = Recording.start(context) else onError("Без доступа к микрофону диктовка не работает")
    }

    fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        val audio = rec.stop() ?: return
        transcribing = true
        scope.launch {
            try {
                val words = transcribe(audio, "audio/mp4")
                if (words.isNotBlank()) onText(if (text.isBlank()) words else "$text $words")
            } catch (e: Exception) {
                onError("Не распознано: ${e.message}")
            }
            transcribing = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(colors.composer)
            .border(1.dp, colors.composerBorder, RoundedCornerShape(26.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        if (attachments.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                attachments.forEach { attachment ->
                    PendingAttachment(attachment) { onAttachments(attachments - attachment) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (text.isEmpty()) Text(if (recorder != null) "Говорите…" else "Спросите Hermes", color = colors.faintText, fontSize = 16.sp)
            BasicTextField(
                value = text,
                onValueChange = onText,
                textStyle = TextStyle(color = colors.text, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(colors.text),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = if (enterSends) ImeAction.Send else ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(onSend = { if (!running) onSend() }),
                singleLine = false,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.Add, "Прикрепить", tint = colors.text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Фото") }, leadingIcon = { Icon(Icons.Outlined.Image, null) },
                        onClick = { menu = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Камера") }, leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) },
                        onClick = {
                            menu = false
                            val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
                            val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
                            cameraFile = file
                            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Файл") }, leadingIcon = { Icon(Icons.Outlined.AttachFile, null) },
                        onClick = { menu = false; files.launch(arrayOf("*/*")) },
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            when {
                transcribing -> CircularProgressIndicator(Modifier.padding(10.dp).size(20.dp), strokeWidth = 2.dp)
                recorder != null -> RoundButton(colors.danger, colors.onContrast, Icons.Filled.Stop, "Закончить диктовку") { stopRecording() }
                else -> IconButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        recorder = Recording.start(context)
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }) { Icon(Icons.Outlined.Mic, "Диктовка", tint = colors.text) }
            }
            Spacer(Modifier.width(4.dp))
            val canSend = text.isNotBlank() || attachments.isNotEmpty()
            when {
                running && !canSend -> RoundButton(colors.contrast, colors.onContrast, Icons.Filled.Stop, "Остановить", onStop)
                running -> RoundButton(colors.contrast, colors.onContrast, Icons.AutoMirrored.Filled.ArrowForward, "Добавить в очередь", onSend)
                else -> RoundButton(
                    if (canSend) colors.contrast else colors.composerBorder,
                    colors.onContrast,
                    Icons.Filled.ArrowUpward,
                    "Отправить",
                    enabled = canSend,
                    onClick = onSend,
                )
            }
        }
    }
}

@Composable
private fun RoundButton(
    background: androidx.compose.ui.graphics.Color,
    tint: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) = RoundButton(background, tint, icon, label, true, onClick)

@Composable
private fun RoundButton(
    background: androidx.compose.ui.graphics.Color,
    tint: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.padding(4.dp).size(36.dp).clip(CircleShape).background(background).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun PendingAttachment(attachment: LocalAttachment, onRemove: () -> Unit) {
    val colors = ChatTheme.colors
    Box {
        if (attachment.image) {
            AsyncImage(attachment.bytes, attachment.name, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
        } else {
            Box(Modifier.size(width = 150.dp, height = 64.dp)) { FileChip(attachment.name) {} }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape).background(colors.contrast).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Close, "Убрать", tint = colors.onContrast, modifier = Modifier.size(14.dp)) }
    }
}

/** Reads a picked file whole; the gateway takes attachments as base64 inside one frame. */
private fun read(context: Context, uri: Uri): LocalAttachment? {
    val resolver = context.contentResolver
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getString(0)?.let { name = it }
            if (!cursor.isNull(1)) size = cursor.getLong(1)
        }
    }
    if (size > MAX_ATTACHMENT) return null
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.size > MAX_ATTACHMENT) return null
    return LocalAttachment(name, resolver.getType(uri), bytes)
}

/** Dictation: AAC in an MP4 box, which the server's speech-to-text takes as audio/mp4. */
class Recording private constructor(private val recorder: MediaRecorder, private val file: File) {
    fun stop(): ByteArray? = try {
        recorder.stop()
        file.readBytes()
    } catch (e: RuntimeException) {
        // Stopped before any sound was recorded.
        null
    } finally {
        recorder.release()
        file.delete()
    }

    companion object {
        fun start(context: Context): Recording? = try {
            val file = File(context.cacheDir, "dictation.m4a")
            val recorder = MediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)
                setAudioEncodingBitRate(64_000)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            Recording(recorder, file)
        } catch (e: Exception) {
            null
        }
    }
}
