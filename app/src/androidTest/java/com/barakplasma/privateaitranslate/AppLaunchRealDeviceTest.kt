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
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.barakplasma.privateaitranslate.ui.MainActivity
import com.barakplasma.privateaitranslate.util.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchRealDeviceTest {
    private val targetContext: Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun launchesMainActivity() {
        targetContext.getSharedPreferences("preferences", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean(Preferences.translateAutomatically, false)
            .commit()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity is MainActivity)
                assertEquals("com.barakplasma.privateaitranslate.debug", activity.packageName)
            }
        }
    }
}
