/*
 * Copyright (c) 2023 You Apps
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.barakplasma.privateaitranslate.ui.components

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.barakplasma.privateaitranslate.R
import com.barakplasma.privateaitranslate.const.ThemeMode
import com.barakplasma.privateaitranslate.obj.ListPreferenceOption
import com.barakplasma.privateaitranslate.ui.MainActivity
import com.barakplasma.privateaitranslate.ui.components.prefs.ListPreferenceDialog
import com.barakplasma.privateaitranslate.util.Preferences

@Composable
fun ThemeModeDialog(
    onDismiss: () -> Unit
) {
    val activity = LocalActivity.current as MainActivity
    ListPreferenceDialog(
        preferenceKey = Preferences.themeModeKey,
        onDismissRequest = {
            onDismiss.invoke()
        },
        options = listOf(
            ListPreferenceOption(
                name = stringResource(R.string.theme_auto),
                value = ThemeMode.AUTO.value,
            ),
            ListPreferenceOption(
                name = stringResource(R.string.theme_light),
                value = ThemeMode.LIGHT.value,
            ),
            ListPreferenceOption(
                name = stringResource(R.string.theme_dark),
                value = ThemeMode.DARK.value,
            ),
            ListPreferenceOption(
                name = stringResource(R.string.theme_black),
                value = ThemeMode.BLACK.value,
            )
        ),
        title = stringResource(R.string.select_theme),
        currentValue = activity.themeMode.value,
        onOptionSelected = {
            activity.themeMode = ThemeMode.entries[it.value]
        }
    )
}
