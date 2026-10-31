package org.evsyukov.hermes.ui.chat

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DaySeparatorTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `today is called today`() {
        compose.setContent { HermesTheme { DaySeparator(LocalDate.now()) } }

        compose.onNodeWithText("Today").assertExists()
    }

    @Test
    fun `yesterday is called yesterday`() {
        compose.setContent { HermesTheme { DaySeparator(LocalDate.now().minusDays(1)) } }

        compose.onNodeWithText("Yesterday").assertExists()
    }

    @Test
    fun `a day of another year shows its day, the month in full and the year`() {
        val year = LocalDate.now().year - 2
        compose.setContent { HermesTheme { DaySeparator(LocalDate.of(year, 3, 5)) } }

        compose.onNodeWithText("5 March $year").assertExists()
    }
}
