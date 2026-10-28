package org.evsyukov.hermes.ui.viewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import org.evsyukov.hermes.ui.theme.ChatTheme
import java.io.File

/**
 * Opens a PDF with the platform renderer. The renderer serves one page at a time, so [mutex] keeps
 * the pages of the list from rendering at once.
 */
private class PdfDocument(file: File) : AutoCloseable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val mutex = Mutex()

    val pageCount: Int get() = renderer.pageCount

    /** Width over height of every page, read once so the list can lay out pages before they render. */
    val ratios: List<Float> = (0 until renderer.pageCount).map { index ->
        renderer.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
    }

    suspend fun render(index: Int, width: Int): Bitmap = mutex.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val height = (width / ratios[index]).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    override fun close() {
        renderer.close()
        descriptor.close()
    }
}

/** Every page of the PDF under one another; pinch or double tap zooms the whole document. */
@Composable
fun PdfPages(file: File, modifier: Modifier = Modifier) {
    var document by remember(file) { mutableStateOf<PdfDocument?>(null) }
    var failure by remember(file) { mutableStateOf<String?>(null) }
    LaunchedEffect(file) {
        try {
            document = withContext(Dispatchers.IO) { PdfDocument(file) }
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        }
    }
    DisposableEffect(file) { onDispose { document?.close() } }

    val doc = document
    when {
        failure != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("PDF не открылся: $failure", color = ChatTheme.colors.secondaryText)
        }
        doc == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> BoxWithConstraints(modifier.fillMaxSize().background(ChatTheme.colors.codeBackground)) {
            // Rendered at twice the width of the screen, so a zoomed page stays sharp.
            val pixels = with(LocalDensity.current) { maxWidth.roundToPx() } * 2
            LazyColumn(
                state = rememberLazyListState(),
                modifier = Modifier.fillMaxSize().zoomable(rememberZoomableState()),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(doc.pageCount) { index -> PdfPage(doc, index, pixels) }
            }
        }
    }
}

@Composable
private fun PdfPage(document: PdfDocument, index: Int, width: Int) {
    var bitmap by remember(document, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(document, index, width) { bitmap = document.render(index, width) }
    Box(
        Modifier.fillMaxWidth().aspectRatio(document.ratios[index]).background(androidx.compose.ui.graphics.Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val page = bitmap
        if (page == null) {
            CircularProgressIndicator()
        } else {
            Image(page.asImageBitmap(), contentDescription = "Страница ${index + 1}", modifier = Modifier.fillMaxSize())
        }
    }
}
