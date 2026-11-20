package com.example.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

data class AppLanguage(
    val code: String,
    val nativeName: String,
    val isRtl: Boolean = false
)

object LocaleHelper {

    val supportedLanguages = listOf(
        AppLanguage("en", "English"),
        AppLanguage("ar", "العربية", isRtl = true),
        AppLanguage("es", "Español"),
        AppLanguage("fr", "Français"),
        AppLanguage("de", "Deutsch"),
        AppLanguage("zh-CN", "简体中文"),
        AppLanguage("hi", "हिन्दी"),
        AppLanguage("pt", "Português"),
        AppLanguage("ru", "Русский"),
        AppLanguage("tr", "Türkçe")
    )

    fun resolveLocale(code: String): Locale {
        return when (code) {
            "zh-CN", "zh" -> Locale.SIMPLIFIED_CHINESE
            "system", "" -> Locale.getDefault()
            else -> Locale.forLanguageTag(code)
        }
    }

    fun isRtl(code: String): Boolean {
        return if (code == "system" || code.isBlank()) {
            Locale.getDefault().language == "ar"
        } else {
            code.startsWith("ar")
        }
    }

    fun wrapContext(base: Context, languageCode: String): Context {
        if (languageCode == "system" || languageCode.isBlank()) return base
        val locale = resolveLocale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
