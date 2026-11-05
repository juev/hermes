package org.evsyukov.hermes.ui.search

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.evsyukov.hermes.AppStrings
import org.evsyukov.hermes.Connection
import org.evsyukov.hermes.R
import org.evsyukov.hermes.data.auth.TokenCipher
import org.evsyukov.hermes.data.remote.ChatSummary
import org.evsyukov.hermes.data.remote.GatewaySocket
import org.evsyukov.hermes.data.remote.SearchHit
import org.evsyukov.hermes.data.server.ServerProfile
import org.evsyukov.hermes.data.server.ServerSession
import org.evsyukov.hermes.data.server.ServerStore
import org.evsyukov.hermes.ui.SearchRequestIdentity
import org.evsyukov.hermes.ui.SearchState
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The row carries the request it was rendered with: the screen hands the captured identity to the
 * action, and the empty label appears only for a request that actually ran. A 360 dp phone with a
 * larger font shows the same states in English and Russian.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp", fontScale = 1.3f, application = Application::class)
class SearchScreenRequestTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val strings = AppStrings(context.resources)
    private val session = ServerSession(
        ServerProfile("home", "Home", "http://127.0.0.1:1"),
        OkHttpClient(),
        ServerStore(context, PlainCipher, scope),
        strings,
    )
    private val connection = Connection(session, GatewaySocket(session.http, scope, strings))

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a row hands the request it was rendered with to the action`() {
        val identity = SearchRequestIdentity(connection, 7)
        var opened: Pair<SearchHit, SearchRequestIdentity?>? = null
        compose.setContent {
            HermesTheme {
                SearchScreen(
                    SearchState(query = "alpha", hits = listOf(hit("s1")), request = identity),
                    onQuery = {}, onOpen = { h, i -> opened = h to i }, onBack = {},
                )
            }
        }

        compose.onNodeWithText("Chat s1").performClick()

        assertThat(opened?.first).isEqualTo(hit("s1"))
        assertThat(opened?.second).isSameInstanceAs(identity)
    }

    @Test
    fun `an empty current request shows the empty label`() {
        compose.setContent {
            HermesTheme {
                SearchScreen(
                    SearchState(query = "alpha", request = SearchRequestIdentity(connection, 1)),
                    onQuery = {}, onOpen = { _, _ -> }, onBack = {},
                )
            }
        }

        compose.onNodeWithText(strings.get(R.string.search_empty)).assertIsDisplayed()
    }

    @Test
    fun `a query without a request yet shows no empty label`() {
        compose.setContent {
            HermesTheme {
                SearchScreen(
                    SearchState(query = "alpha"),
                    onQuery = {}, onOpen = { _, _ -> }, onBack = {},
                )
            }
        }

        compose.onNodeWithText(strings.get(R.string.search_empty)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h640dp")
    fun `the empty label is Russian on a Russian phone`() {
        compose.setContent {
            HermesTheme {
                SearchScreen(
                    SearchState(query = "alpha", request = SearchRequestIdentity(connection, 1)),
                    onQuery = {}, onOpen = { _, _ -> }, onBack = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.search_empty)).assertIsDisplayed()
        assertThat(context.getString(R.string.search_empty)).isEqualTo("Ничего не нашлось")
    }

    private fun hit(id: String) = SearchHit(
        ChatSummary(
            id = id, title = "Chat $id", preview = "", source = "telegram", model = null,
            startedAt = 1.0, lastActive = 2.0, messageCount = 1,
            pinned = false, archived = false, active = false,
        ),
        "snippet",
        null,
    )

    private object PlainCipher : TokenCipher {
        override fun encrypt(plain: String) = plain
        override fun decrypt(sealed: String) = sealed
    }
}
