package org.evsyukov.hermes.ui.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.evsyukov.hermes.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
// A bare application: the screen needs none of the services HermesApplication starts.
@Config(sdk = [36], qualifiers = "w411dp-h891dp", application = Application::class)
class LicensesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the app license opens from the list and back returns to it`() {
        compose.setContent { HermesTheme { LicensesScreen(onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Hermes for Android") > 0 }
        compose.onNodeWithText("Hermes for Android").performClick()
        compose.onNodeWithText("MIT License").assertIsDisplayed()
        compose.onNodeWithText("Copyright (c) 2026 Denis Evsyukov").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("This app").assertIsDisplayed()
    }

    @Test
    fun `a library opens its license text`() {
        compose.setContent { HermesTheme { LicensesScreen(onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTextCount("Hermes for Android") > 0 }
        compose.onNodeWithTag(LICENSES_LIST).performScrollToNode(hasText("Protocol Buffers [Core]"))
        compose.onNodeWithText("Protocol Buffers [Core]").performClick()
        compose.onNodeWithText("Copyright 2008 Google Inc.  All rights reserved.").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes().size
}
