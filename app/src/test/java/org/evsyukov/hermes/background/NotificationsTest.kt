package org.evsyukov.hermes.background

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class NotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun allowNotifications() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted(tag: String, id: Int) = shadowOf(manager).getNotification(tag, id)

    @Test
    fun `reading one chat takes only that chat out of the shade`() {
        val notifications = Notifications(context)
        notifications.answer("read-chat", "Read chat", "an answer", error = false)
        notifications.activity("unread-chat", "Unread chat", "another answer")
        assertThat(posted("chat", "read-chat".hashCode())).isNotNull()
        assertThat(posted("chat", "unread-chat".hashCode())).isNotNull()

        notifications.cancelChat("read-chat")

        assertThat(posted("chat", "read-chat".hashCode())).isNull()
        assertThat(posted("chat", "unread-chat".hashCode())).isNotNull()
    }

    @Test
    fun `a new message after the chat was read gets its own notification`() {
        val notifications = Notifications(context)
        notifications.answer("chat", "Chat", "first", error = false)
        notifications.cancelChat("chat")
        notifications.answer("chat", "Chat", "second", error = false)

        val shown = posted("chat", "chat".hashCode())
        assertThat(shown).isNotNull()
        assertThat(shadowOf(shown!!).contentText?.toString()).isEqualTo("second")
    }

    @Test
    fun `an approval is not a message notification of the chat and stays`() {
        val notifications = Notifications(context)
        notifications.answer("chat", "Chat", "an answer", error = false)
        notifications.request("chat", "request-1", "Approval", "Run it?", listOf("once", "deny"))

        notifications.cancelChat("chat")

        assertThat(posted("chat", "chat".hashCode())).isNull()
        assertThat(posted("request", "request-1".hashCode())).isNotNull()
    }
}
