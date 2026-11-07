package org.evsyukov.hermes.ui.chat

import android.content.Context
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
import androidx.compose.material.icons.outlined.PhotoCamera
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import org.evsyukov.hermes.R
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
    onError: (String) -> Unit,
    onOpen: (LocalAttachment) -> Unit,
) {
    val colors = ChatTheme.colors
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun add(uris: List<Uri>) {
        scope.launch {
            val picked = withContext(Dispatchers.IO) { uris.mapNotNull { readAttachment(context, it) } }
            if (picked.size < uris.size) onError(resources.getString(R.string.composer_file_too_big))
            onAttachments(attachments + picked)
        }
    }

    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { add(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraFile
        if (ok && file != null) onAttachments(attachments + LocalAttachment("photo_${System.currentTimeMillis()}.jpg", "image/jpeg", file.readBytes()))
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
                    PendingAttachment(attachment, onOpen = { onOpen(attachment) }) { onAttachments(attachments - attachment) }
                }
            }
        }
        // One row: the buttons sit at the bottom edge, so a field grown past one line keeps them next to its last line.
        val canSend = text.isNotBlank() || attachments.isNotEmpty()
        Row(verticalAlignment = Alignment.Bottom) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.Add, stringResource(R.string.composer_attach), tint = colors.text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.composer_photo)) }, leadingIcon = { Icon(Icons.Outlined.Image, null) },
                        onClick = { menu = false; photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.composer_camera)) }, leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) },
                        onClick = {
                            menu = false
                            val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
                            val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
                            cameraFile = file
                            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.composer_file)) }, leadingIcon = { Icon(Icons.Outlined.AttachFile, null) },
                        onClick = { menu = false; files.launch(arrayOf("*/*")) },
                    )
                }
            }
            // The vertical padding centres one line of text on the 48 dp buttons.
            Box(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 12.dp)) {
                val hint = stringResource(R.string.composer_hint)
                // The hint is a visual placeholder only: the field carries the accessible name, so
                // an empty composer does not announce the same purpose twice.
                if (text.isEmpty()) Text(hint, color = colors.faintText, fontSize = 16.sp, modifier = Modifier.clearAndSetSemantics { testTag = "composerHint" })
                BasicTextField(
                    value = text,
                    onValueChange = onText,
                    textStyle = TextStyle(color = colors.text, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(colors.text),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = if (enterSends) ImeAction.Send else ImeAction.Default,
                    ),
                    // A follow-up typed while a turn runs is queued by the send key, once, like the
                    // queue button; an empty field does not pretend to submit anything.
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    singleLine = false,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).semantics { contentDescription = hint },
                )
            }
            Spacer(Modifier.width(4.dp))
            ButtonSlot {
                when {
                    // Stop keeps the last place while a turn runs: a follow-up adds Queue beside it
                    // instead of taking its place, so stopping never throws the draft away.
                    running -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (canSend) {
                            RoundButton(colors.composerBorder, colors.text, Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.composer_queue), onSend)
                        }
                        RoundButton(colors.contrast, colors.onContrast, Icons.Filled.Stop, stringResource(R.string.composer_stop), onStop)
                    }
                    else -> RoundButton(
                        if (canSend) colors.contrast else colors.composerBorder,
                        colors.onContrast,
                        Icons.Filled.ArrowUpward,
                        stringResource(R.string.common_send),
                        enabled = canSend,
                        onClick = onSend,
                    )
                }
            }
        }
    }
}

/** Holds a button smaller than an [IconButton] at the same 48 dp height, so the row's bottom-aligned buttons share one centre line. */
@Composable
private fun ButtonSlot(content: @Composable () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { content() }
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
        Modifier.padding(4.dp).size(36.dp).clip(CircleShape).background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun PendingAttachment(attachment: LocalAttachment, onOpen: () -> Unit, onRemove: () -> Unit) {
    val colors = ChatTheme.colors
    val removeLabel = stringResource(R.string.composer_remove)
    // The remove button owns a real 48 dp slot beside the preview instead of a badge whose expanded
    // touch area would compete with the preview below it.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            if (attachment.image) {
                AsyncImage(
                    attachment.bytes, attachment.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen),
                )
            } else {
                Box(Modifier.size(width = 150.dp, height = 64.dp)) { FileChip(attachment.name, onOpen) }
            }
        }
        Box(
            Modifier.size(48.dp).clickable(role = Role.Button, onClick = onRemove),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier.padding(start = 4.dp).size(20.dp).clip(CircleShape).background(colors.contrast),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, removeLabel, tint = colors.onContrast, modifier = Modifier.size(14.dp)) }
        }
    }
}

/** Reads a picked or shared file whole; the gateway takes attachments as base64 inside one frame. */
internal fun readAttachment(context: Context, uri: Uri): LocalAttachment? {
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
