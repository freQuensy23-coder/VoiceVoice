package com.voicevoice.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun productionScreenShowsSetupProvidersAndHistory() {
        composeTestRule.onNodeWithText("VoiceVoice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Setup").assertIsDisplayed()
        composeTestRule.onNodeWithTag("voicevoice-list").performScrollToIndex(1)
        composeTestRule.onNodeWithText("Providers and behavior").assertIsDisplayed()
        composeTestRule.onNodeWithTag("voicevoice-list").performScrollToIndex(3)
        composeTestRule.onNodeWithText("History").assertIsDisplayed()
    }
}
