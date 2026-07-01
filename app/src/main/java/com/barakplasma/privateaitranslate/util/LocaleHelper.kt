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

package com.barakplasma.privateaitranslate.util

import android.content.Context
import android.content.res.Configuration
import com.barakplasma.privateaitranslate.R
import com.barakplasma.privateaitranslate.db.obj.DbLanguage
import java.util.Locale

object LocaleHelper {
    fun wrapContext(context: Context): Context {
        val locale = getSelectedLocale(context)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        return context.createConfigurationContext(config)
    }

    fun updateLanguage(context: Context) {
        val localizedContext = wrapContext(context)
        updateResources(context, localizedContext.resources.configuration)
    }

    private fun updateResources(context: Context, config: Configuration) {
        context.resources.apply {
            @Suppress("DEPRECATION")
            updateConfiguration(config, displayMetrics)
        }
    }

    private fun getSelectedLocale(context: Context): Locale {
        val langPref = Preferences.get(Preferences.appLanguageKey, "")
        if (langPref.isEmpty()) {
            return context.resources.configuration.locales[0]
        }

        return Locale.forLanguageTag(langPref.replace("-r", "-"))
    }

    fun getLanguages(context: Context) = listOf(
        DbLanguage("en", "English"),
        DbLanguage("ar", "Arabic"),
        DbLanguage("az", "Azerbaijani"),
        DbLanguage("be", "Belarusian"),
        DbLanguage("bg", "Bulgarian"),
        DbLanguage("bn", "Bengali"),
        DbLanguage("ca", "Catalan"),
        DbLanguage("cs", "Czech"),
        DbLanguage("da", "Danish"),
        DbLanguage("de", "German"),
        DbLanguage("el", "Greek"),
        DbLanguage("es", "Spanish"),
        DbLanguage("et", "Estonian"),
        DbLanguage("fa", "Persian"),
        DbLanguage("fi", "Finnish"),
        DbLanguage("fil", "Filipino"),
        DbLanguage("fr-rFR", "French"),
        DbLanguage("he", "Hebrew"),
        DbLanguage("hi", "Hindi"),
        DbLanguage("hu", "Hungarian"),
        DbLanguage("ia", "Interlingua"),
        DbLanguage("id", "Indonesian"),
        DbLanguage("it", "Italian"),
        DbLanguage("ja", "Japanese"),
        DbLanguage("kab", "Kabyle"),
        DbLanguage("ko", "Korean"),
        DbLanguage("lt", "Lithuanian"),
        DbLanguage("lv", "Latvian"),
        DbLanguage("ml", "Malayalam"),
        DbLanguage("ms", "Malay"),
        DbLanguage("nb-rNO", "Norwegian Bokmål"),
        DbLanguage("nn", "Norwegian Nynorsk"),
        DbLanguage("or", "Odia"),
        DbLanguage("pa", "Punjabi"),
        DbLanguage("pa-rPK", "Punjabi (Pakistan)"),
        DbLanguage("pl", "Polish"),
        DbLanguage("pt", "Portuguese"),
        DbLanguage("pt-rBR", "Portuguese (Brazil)"),
        DbLanguage("ro", "Romanian"),
        DbLanguage("ru", "Russian"),
        DbLanguage("sat", "Santali"),
        DbLanguage("sc", "Sardinian"),
        DbLanguage("sk", "Slovak"),
        DbLanguage("sr", "Serbian"),
        DbLanguage("sv", "Swedish"),
        DbLanguage("ta", "Tamil"),
        DbLanguage("tr", "Turkish"),
        DbLanguage("tt", "Tatar"),
        DbLanguage("uk", "Ukrainian"),
        DbLanguage("ur", "Urdu"),
        DbLanguage("vi", "Vietnamese"),
        DbLanguage("zh-rCN", "Chinese (Simplified)"),
        DbLanguage("zh-rTW", "Chinese (Traditional)")
    ).sortedBy { it.name }.toMutableList()
        .apply {
            add(0, DbLanguage("", context.getString(R.string.system)))
        }
}
