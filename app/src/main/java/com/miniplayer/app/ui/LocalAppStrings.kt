package com.miniplayer.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import com.miniplayer.app.core.i18n.AppLanguage
import com.miniplayer.app.core.i18n.AppLocaleHolder
import com.miniplayer.app.core.i18n.AppStrings
import com.miniplayer.app.core.i18n.ResolvedLanguage
import java.util.Locale

val LocalAppStrings = staticCompositionLocalOf<AppStrings> { AppStrings.current() }
val LocalResolvedLanguage = staticCompositionLocalOf<ResolvedLanguage> { AppLocaleHolder.activeLanguage }

@Composable
fun ProvideAppLanguage(
    preferredLanguage: AppLanguage,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val systemLocale: Locale = configuration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    val resolved = preferredLanguage.resolve(systemLocale)
    val strings = AppStrings.forLanguage(resolved)

    SideEffect {
        AppLocaleHolder.setPreference(preferredLanguage, systemLocale)
    }

    CompositionLocalProvider(
        LocalAppStrings provides strings,
        LocalResolvedLanguage provides resolved,
        content = content,
    )
}
