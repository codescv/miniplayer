package com.miniplayer.app.core.i18n

import java.util.Locale

/**
 * User-selectable application UI language persisted in `app_settings`.
 * Defaults to [SYSTEM] so the app follows the OS locale (`zh*` -> Simplified Chinese,
 * `ja*` -> Japanese, anything else -> English).
 */
enum class AppLanguage(val storageValue: String) {
    SYSTEM("system"),
    ZH("zh"),
    EN("en"),
    JA("ja"),
    ;

    fun resolve(systemLocale: Locale = Locale.getDefault()): ResolvedLanguage = when (this) {
        ZH -> ResolvedLanguage.ZH
        EN -> ResolvedLanguage.EN
        JA -> ResolvedLanguage.JA
        SYSTEM -> resolveSystemLocale(systemLocale)
    }

    companion object {
        fun fromStorageValue(raw: String?): AppLanguage {
            val normalized = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return when (normalized) {
                "zh", "zh-cn", "zh_cn", "zh-hans" -> ZH
                "en", "en-us", "en_us" -> EN
                "ja", "ja-jp", "ja_jp" -> JA
                else -> SYSTEM
            }
        }

        fun resolveSystemLocale(locale: Locale = Locale.getDefault()): ResolvedLanguage {
            val lang = locale.language.lowercase(Locale.ROOT)
            return when {
                lang == "zh" || lang.startsWith("zh-") || lang.startsWith("zh_") -> ResolvedLanguage.ZH
                lang == "ja" || lang.startsWith("ja-") || lang.startsWith("ja_") -> ResolvedLanguage.JA
                else -> ResolvedLanguage.EN
            }
        }
    }
}

enum class ResolvedLanguage(
    val htmlLang: String,
    val bcp47Tag: String,
) {
    ZH(htmlLang = "zh-CN", bcp47Tag = "zh-CN"),
    EN(htmlLang = "en", bcp47Tag = "en"),
    JA(htmlLang = "ja", bcp47Tag = "ja"),
}

/**
 * Process-wide reactive holder for the active resolved language, allowing pure-JVM core layers
 * (ViewModel snackbars, LAN Web Studio server, feedback builder, stem separation errors) to read
 * the active language immediately without requiring an Android `Context` or app restart.
 */
object AppLocaleHolder {
    @Volatile
    private var preferred: AppLanguage = AppLanguage.SYSTEM

    @Volatile
    private var systemLocaleOverride: Locale? = null

    val preferredLanguage: AppLanguage
        get() = preferred

    val activeLanguage: ResolvedLanguage
        get() = preferred.resolve(systemLocaleOverride ?: Locale.getDefault())

    fun setPreference(language: AppLanguage, systemLocale: Locale? = null) {
        preferred = language
        if (systemLocale != null) {
            systemLocaleOverride = systemLocale
        }
    }

    fun updateSystemLocale(locale: Locale?) {
        systemLocaleOverride = locale
    }

    fun resetForTesting() {
        preferred = AppLanguage.SYSTEM
        systemLocaleOverride = null
    }
}
