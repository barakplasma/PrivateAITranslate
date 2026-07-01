/*
 * Copyright (c) 2026 You Apps
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.barakplasma.privateaitranslate

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.barakplasma.privateaitranslate.ui.MainActivity
import com.barakplasma.privateaitranslate.util.Preferences
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeRealDeviceTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun opensSettingsAndShowsCoreOptions() {
        launchWithPrefs().use {
            val openMenuDescription = targetContext.getString(com.barakplasma.privateaitranslate.R.string.open_menu)
            composeRule.onNodeWithContentDescription(openMenuDescription).performClick()
            composeRule.onNodeWithText("Settings").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("High security mode").assertIsDisplayed()
            composeRule.onNodeWithText("Selected engine").assertIsDisplayed()
        }
    }

    @Test
    fun acceptsLeftToRightTextInput() {
        launchWithPrefs().use {
            composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Hello world")
            composeRule.waitForIdle()

            composeRule.onNodeWithText("Hello world").assertIsDisplayed()
            composeRule.onNodeWithText("Translate").assertIsDisplayed()
        }
    }

    @Test
    fun acceptsRightToLeftTextInput() {
        launchWithPrefs().use {
            composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("שלום עולם")
            composeRule.waitForIdle()
            composeRule.onNodeWithText("שלום עולם").assertIsDisplayed()
            composeRule.onNodeWithText("Translate").assertIsDisplayed()
        }
    }

    private fun launchWithPrefs(appLanguage: String = ""): ActivityScenario<MainActivity> {
        targetContext.getSharedPreferences("preferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString(Preferences.appLanguageKey, appLanguage)
            .putBoolean(Preferences.translateAutomatically, false)
            .commit()

        return ActivityScenario.launch(MainActivity::class.java).also {
            composeRule.waitForIdle()
        }
    }
}
