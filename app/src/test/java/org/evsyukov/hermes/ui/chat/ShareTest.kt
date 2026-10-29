package org.evsyukov.hermes.ui.chat

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.hermes.MainActivity
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class ShareTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun file(name: String, content: String): Uri = Uri.fromFile(File(app.cacheDir, name).apply { writeText(content) })

    private fun handlers(intent: Intent): List<String> =
        app.packageManager.queryIntentActivities(intent.setPackage(app.packageName), 0).map { it.activityInfo.name }

    @Test
    fun `the share sheet offers Hermes for links, pictures and any files`() {
        assertThat(handlers(Intent(Intent.ACTION_SEND).setType("text/plain"))).containsExactly(MainActivity::class.java.name)
        assertThat(handlers(Intent(Intent.ACTION_SEND).setType("image/png"))).containsExactly(MainActivity::class.java.name)
        assertThat(handlers(Intent(Intent.ACTION_SEND).setType("application/pdf"))).containsExactly(MainActivity::class.java.name)
        assertThat(handlers(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*"))).containsExactly(MainActivity::class.java.name)
    }

    @Test
    fun `a shared link goes to the field`() {
        val shared = readShared(app, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, " https://example.org/a \n"))
        assertThat(shared!!.text).isEqualTo("https://example.org/a")
        assertThat(shared.attachments).isEmpty()
        assertThat(shared.skipped).isEqualTo(0)
    }

    @Test
    fun `the subject stands in only when there is no text`() {
        val both = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "https://example.org").putExtra(Intent.EXTRA_SUBJECT, "Example")
        assertThat(readShared(app, both)!!.text).isEqualTo("https://example.org")
        val subject = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Example")
        assertThat(readShared(app, subject)!!.text).isEqualTo("Example")
    }

    @Test
    fun `a shared file becomes an attachment with its name and contents`() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, file("notes.md", "# Notes"))
        val shared = readShared(app, intent)!!
        assertThat(shared.text).isEmpty()
        assertThat(shared.attachments.map { it.name }).containsExactly("notes.md")
        assertThat(String(shared.attachments.single().bytes)).isEqualTo("# Notes")
    }

    @Test
    fun `several files come together, and one that cannot be read is counted`() {
        val uris = arrayListOf(file("a.txt", "a"), Uri.parse("content://gone.example/1"), file("b.txt", "b"))
        val shared = readShared(app, Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris))!!
        assertThat(shared.attachments.map { it.name }).containsExactly("a.txt", "b.txt").inOrder()
        assertThat(shared.skipped).isEqualTo(1)
    }

    @Test
    fun `an intent that shares nothing is not a share`() {
        assertThat(readShared(app, Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org")))).isNull()
        assertThat(readShared(app, Intent(Intent.ACTION_SEND).setType("text/plain"))).isNull()
    }

    @Test
    fun `a quick action asks first and then gives what was shared`() {
        assertThat(quickMessage("Summarize this.", " https://example.org ")).isEqualTo("Summarize this.\n\nhttps://example.org")
        assertThat(quickMessage("Summarize this.", "  ")).isEqualTo("Summarize this.")
    }

    @Test
    fun `the quick actions offer a summary, related things and a task`() {
        var picked: QuickAction? = null
        compose.setContent { HermesTheme { QuickActions(onAction = { picked = it }) } }
        compose.onNodeWithText("Summarize").assertExists()
        compose.onNodeWithText("Find related").assertExists()
        compose.onNodeWithText("Create a task").performClick()
        assertThat(picked).isEqualTo(QuickAction.TASK)
        assertThat(app.getString(QuickAction.TASK.prompt))
            .isEqualTo("Create a task from this in the service that fits best among those connected, and give me the link.")
    }
}
