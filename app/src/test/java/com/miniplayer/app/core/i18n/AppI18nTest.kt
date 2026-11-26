package com.miniplayer.app.core.i18n

import com.miniplayer.app.core.db.MediaRepository
import com.miniplayer.app.core.db.MiniPlayerDatabase
import com.miniplayer.app.core.model.AlbumSummary
import com.miniplayer.app.core.model.StemType
import com.miniplayer.app.lan.LanStudioWebApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * Comprehensive verification of MiniPlayer Android i18n unification:
 * 1. Language resolution (`system` -> zh/ja/en) & explicit overrides
 * 2. Settings persistence round-trip & reactive AppLocaleHolder updates
 * 3. 100% key parity, non-empty values, and placeholder parity across zh/en/ja
 * 4. Zero bilingual CN+EN leaks and strict single-language purity
 * 5. Embedded LAN Web Studio localized HTML rendering (`zh`, `en`, `ja`)
 * 6. Pure-JVM invariant on `com.miniplayer.app.core.i18n`
 */
class AppI18nTest {

    @After
    fun tearDown() {
        AppLocaleHolder.resetForTesting()
    }

    @Test
    fun systemLanguageResolvesZhJaAndFallbackEn() {
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale.SIMPLIFIED_CHINESE))
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale.TRADITIONAL_CHINESE))
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale.forLanguageTag("zh-HK")))
        assertEquals(ResolvedLanguage.ZH, AppLanguage.SYSTEM.resolve(Locale.forLanguageTag("zh-Hans")))

        assertEquals(ResolvedLanguage.JA, AppLanguage.SYSTEM.resolve(Locale.JAPANESE))
        assertEquals(ResolvedLanguage.JA, AppLanguage.SYSTEM.resolve(Locale.JAPAN))

        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.US))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.UK))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.FRANCE))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.GERMANY))
        assertEquals(ResolvedLanguage.EN, AppLanguage.SYSTEM.resolve(Locale.KOREA))

        // Explicit language overrides any OS locale
        assertEquals(ResolvedLanguage.EN, AppLanguage.EN.resolve(Locale.SIMPLIFIED_CHINESE))
        assertEquals(ResolvedLanguage.ZH, AppLanguage.ZH.resolve(Locale.US))
        assertEquals(ResolvedLanguage.JA, AppLanguage.JA.resolve(Locale.US))
    }

    @Test
    fun storageValueParsingAndLocaleHolderReactImmediately() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorageValue(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorageValue(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorageValue("system"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromStorageValue("unknown"))
        assertEquals(AppLanguage.ZH, AppLanguage.fromStorageValue("zh"))
        assertEquals(AppLanguage.ZH, AppLanguage.fromStorageValue("zh-CN"))
        assertEquals(AppLanguage.EN, AppLanguage.fromStorageValue("en"))
        assertEquals(AppLanguage.EN, AppLanguage.fromStorageValue("EN_US"))
        assertEquals(AppLanguage.JA, AppLanguage.fromStorageValue("ja"))
        assertEquals(AppLanguage.JA, AppLanguage.fromStorageValue("ja-JP"))

        AppLocaleHolder.setPreference(AppLanguage.SYSTEM, Locale.JAPAN)
        assertEquals(ResolvedLanguage.JA, AppLocaleHolder.activeLanguage)

        AppLocaleHolder.setPreference(AppLanguage.EN)
        assertEquals(ResolvedLanguage.EN, AppLocaleHolder.activeLanguage)

        AppLocaleHolder.setPreference(AppLanguage.ZH)
        assertEquals(ResolvedLanguage.ZH, AppLocaleHolder.activeLanguage)

        AppLocaleHolder.setPreference(AppLanguage.SYSTEM, Locale.FRANCE)
        assertEquals(ResolvedLanguage.EN, AppLocaleHolder.activeLanguage)
    }

    @Test
    fun settingsPersistenceRoundTripAcrossRepositoryRestart() = runBlocking {
        val backend = MiniPlayerDatabase.createInMemoryBackend()
        val repo1 = MediaRepository(backend)

        // Default is System Default
        assertEquals(AppLanguage.SYSTEM, repo1.settings.value.appLanguage)

        for (target in listOf(AppLanguage.EN, AppLanguage.JA, AppLanguage.ZH, AppLanguage.SYSTEM)) {
            repo1.updateSettings { it.copy(appLanguage = target) }
            assertEquals(target, repo1.settings.value.appLanguage)
            assertEquals(target, AppLocaleHolder.preferredLanguage)

            // Re-open repository over the same backend to verify persistence round-trip
            val reopened = MediaRepository(backend)
            assertEquals(
                "Persisted appLanguage should round-trip for $target",
                target,
                reopened.settings.value.appLanguage,
            )
        }
    }

    @Test
    fun appStringsHave100PercentParityNonEmptyValuesAndMatchingPlaceholders() {
        val stringGetters = AppStrings::class.java.declaredMethods
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    it.returnType == String::class.java &&
                    it.name.startsWith("get")
            }
            .sortedBy { it.name }

        assertTrue("Expected rich AppStrings table (>200 keys), got ${stringGetters.size}", stringGetters.size > 200)

        val placeholderRegex = Regex("""\{\d+\}""")
        val bilingualLeakRegex = Regex("""\((Title|Artist|Album|Album Artist|Year|Track #|Count-In)\)""")
        val cjkOrKanaRegex = Regex("""[\u4E00-\u9FFF\u3040-\u30FF]""")
        val allowedNativeLangFields = setOf("languageZhNative", "languageJaNative")

        for (getter in stringGetters) {
            val fieldName = getter.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
            val zh = getter.invoke(AppStrings.ZH) as String
            val en = getter.invoke(AppStrings.EN) as String
            val ja = getter.invoke(AppStrings.JA) as String

            assertTrue("AppStrings.ZH.$fieldName must not be blank", zh.isNotBlank())
            assertTrue("AppStrings.EN.$fieldName must not be blank", en.isNotBlank())
            assertTrue("AppStrings.JA.$fieldName must not be blank", ja.isNotBlank())

            val zhPlaceholders = placeholderRegex.findAll(zh).map { it.value }.toSet()
            val enPlaceholders = placeholderRegex.findAll(en).map { it.value }.toSet()
            val jaPlaceholders = placeholderRegex.findAll(ja).map { it.value }.toSet()
            assertEquals("Placeholder mismatch ZH vs EN on AppStrings.$fieldName", zhPlaceholders, enPlaceholders)
            assertEquals("Placeholder mismatch ZH vs JA on AppStrings.$fieldName", zhPlaceholders, jaPlaceholders)

            assertFalse("Bilingual leak in ZH.$fieldName: $zh", bilingualLeakRegex.containsMatchIn(zh))
            assertFalse("Bilingual leak in EN.$fieldName: $en", bilingualLeakRegex.containsMatchIn(en))
            assertFalse("Bilingual leak in JA.$fieldName: $ja", bilingualLeakRegex.containsMatchIn(ja))
            assertFalse("Forbidden term '练琴' in ZH.$fieldName: $zh", zh.contains("练琴"))

            if (fieldName !in allowedNativeLangFields) {
                assertFalse(
                    "Unexpected CJK/Kana character in EN.$fieldName: $en",
                    cjkOrKanaRegex.containsMatchIn(en),
                )
            }
        }

        // Stem labels are strictly single-language (no English "Bass" in Chinese!)
        assertEquals("贝斯", AppStrings.ZH.stemLabel(StemType.BASS))
        assertEquals("Bass", AppStrings.EN.stemLabel(StemType.BASS))
        assertEquals("ベース", AppStrings.JA.stemLabel(StemType.BASS))
        for (stem in StemType.entries) {
            assertFalse("ZH stem label must not contain ASCII letters for $stem", AppStrings.ZH.stemLabel(stem).any { it in 'A'..'Z' || it in 'a'..'z' })
            assertTrue(AppStrings.EN.stemLabel(stem).isNotBlank())
            assertTrue(AppStrings.JA.stemLabel(stem).isNotBlank())
        }

        // Preset section labels parity & localization
        assertEquals(AppStrings.ZH.presetSectionLabels.size, AppStrings.EN.presetSectionLabels.size)
        assertEquals(AppStrings.ZH.presetSectionLabels.size, AppStrings.JA.presetSectionLabels.size)
        assertFalse("ZH preset section labels should be localized, not English 'Intro'", "Intro" in AppStrings.ZH.presetSectionLabels)
        assertFalse("ZH preset section labels should not contain 'Chorus'", "Chorus" in AppStrings.ZH.presetSectionLabels)
        assertTrue("EN preset section labels should include 'Chorus'", "Chorus" in AppStrings.EN.presetSectionLabels)
        assertTrue("JA preset section labels should include 'サビ'", "サビ" in AppStrings.JA.presetSectionLabels)

        // Internal sentinels map cleanly per language while real user metadata stays untouched
        assertEquals("未知专辑", AppStrings.ZH.displayAlbum(AlbumSummary.UNKNOWN_ALBUM_TITLE))
        assertEquals("Unknown Album", AppStrings.EN.displayAlbum(AlbumSummary.UNKNOWN_ALBUM_TITLE))
        assertEquals("不明なアルバム", AppStrings.JA.displayAlbum(AlbumSummary.UNKNOWN_ALBUM_TITLE))
        assertEquals("Abbey Road", AppStrings.JA.displayAlbum("Abbey Road"))

        assertEquals("群星", AppStrings.ZH.displayArtist(AlbumSummary.VARIOUS_ARTISTS_KEY))
        assertEquals("Various Artists", AppStrings.EN.displayArtist(AlbumSummary.VARIOUS_ARTISTS_KEY))
        assertEquals("ヴァリアス・アーティスト", AppStrings.JA.displayArtist(AlbumSummary.VARIOUS_ARTISTS_KEY))
        assertEquals("結束バンド", AppStrings.EN.displayArtist("結束バンド"))
    }

    @Test
    fun lanWebStringsHave100PercentParityZeroBilingualLeaksAndCompleteJsKeyCoverage() {
        val stringGetters = LanWebStrings::class.java.declaredMethods
            .filter {
                !Modifier.isStatic(it.modifiers) &&
                    it.parameterCount == 0 &&
                    it.returnType == String::class.java &&
                    it.name.startsWith("get")
            }
            .sortedBy { it.name }

        assertTrue("Expected rich LanWebStrings table (>150 keys), got ${stringGetters.size}", stringGetters.size > 150)

        val placeholderRegex = Regex("""\{\d+\}""")
        val bilingualLeakRegex = Regex("""\((Title|Artist|Album|Album Artist|Year|Track #|Count-In)\)""")
        val cjkOrKanaRegex = Regex("""[\u4E00-\u9FFF\u3040-\u30FF]""")

        for (getter in stringGetters) {
            val fieldName = getter.name.removePrefix("get").replaceFirstChar { it.lowercaseChar() }
            val zh = getter.invoke(LanWebStrings.ZH) as String
            val en = getter.invoke(LanWebStrings.EN) as String
            val ja = getter.invoke(LanWebStrings.JA) as String

            assertTrue("LanWebStrings.ZH.$fieldName must not be blank", zh.isNotBlank())
            assertTrue("LanWebStrings.EN.$fieldName must not be blank", en.isNotBlank())
            assertTrue("LanWebStrings.JA.$fieldName must not be blank", ja.isNotBlank())

            val zhPlaceholders = placeholderRegex.findAll(zh).map { it.value }.toSet()
            val enPlaceholders = placeholderRegex.findAll(en).map { it.value }.toSet()
            val jaPlaceholders = placeholderRegex.findAll(ja).map { it.value }.toSet()
            assertEquals("Placeholder mismatch ZH vs EN on LanWebStrings.$fieldName", zhPlaceholders, enPlaceholders)
            assertEquals("Placeholder mismatch ZH vs JA on LanWebStrings.$fieldName", zhPlaceholders, jaPlaceholders)

            assertFalse("Bilingual leak in ZH.$fieldName: $zh", bilingualLeakRegex.containsMatchIn(zh))
            assertFalse("Bilingual leak in EN.$fieldName: $en", bilingualLeakRegex.containsMatchIn(en))
            assertFalse("Bilingual leak in JA.$fieldName: $ja", bilingualLeakRegex.containsMatchIn(ja))
            assertFalse("Forbidden term '练琴' in ZH.$fieldName: $zh", zh.contains("练琴"))
            assertFalse("AUDIO PREVIEW must not appear in ZH.$fieldName", zh.contains("AUDIO PREVIEW"))
            assertFalse("AUDIO PREVIEW must not appear in JA.$fieldName", ja.contains("AUDIO PREVIEW"))
            assertFalse("Unexpected CJK/Kana in EN.$fieldName: $en", cjkOrKanaRegex.containsMatchIn(en))
        }

        // Verify every t('key') referenced in LanStudioWebApp.kt exists in toJsMap()
        val jsMapKeys = LanWebStrings.ZH.toJsMap().keys
        assertEquals(jsMapKeys, LanWebStrings.EN.toJsMap().keys)
        assertEquals(jsMapKeys, LanWebStrings.JA.toJsMap().keys)

        val repoRoot = locateRepoRoot()
        val webAppSrc = File(repoRoot, "app/src/main/java/com/miniplayer/app/lan/LanStudioWebApp.kt").readText()
        val missingKeys = Regex("""\bt\('([a-zA-Z0-9_]+)'""")
            .findAll(webAppSrc)
            .map { it.groupValues[1] }
            .filter { it !in jsMapKeys }
            .toSet()
        assertTrue("Missing JS I18N keys in LanWebStrings.toJsMap(): $missingKeys", missingKeys.isEmpty())
    }

    @Test
    fun lanStudioWebAppRendersCleanSingleLanguageHtmlForZhEnJa() {
        val cjkOrKanaRegex = Regex("""[\u4E00-\u9FFF\u3040-\u30FF]""")

        val htmlZh = LanStudioWebApp.renderHtml(ResolvedLanguage.ZH)
        assertTrue(htmlZh.contains("<html lang=\"zh-CN\">"))
        assertTrue(htmlZh.contains("<title>MiniPlayer · PC 局域网 Web 管理台</title>"))
        assertTrue(htmlZh.contains("音频预听"))
        assertFalse(htmlZh.contains("AUDIO PREVIEW"))
        assertFalse(htmlZh.contains("标题 (Title)"))
        assertFalse(htmlZh.contains("练琴"))
        assertTrue(htmlZh.contains("return esc(jsEsc);"))
        assertTrue(htmlZh.contains("syncUrlToken();"))

        val htmlEn = LanStudioWebApp.renderHtml(ResolvedLanguage.EN)
        assertTrue(htmlEn.contains("<html lang=\"en\">"))
        assertTrue(htmlEn.contains("<title>MiniPlayer · PC LAN Web Studio</title>"))
        assertTrue(htmlEn.contains("AUDIO PREVIEW"))
        assertTrue(htmlEn.contains("Wireless Transfer &amp; Folders"))
        assertFalse("English LAN Web Studio HTML must contain zero CJK/Kana characters", cjkOrKanaRegex.containsMatchIn(htmlEn))
        assertTrue(htmlEn.contains("return esc(jsEsc);"))
        assertTrue(htmlEn.contains("syncUrlToken();"))

        val htmlJa = LanStudioWebApp.renderHtml(ResolvedLanguage.JA)
        assertTrue(htmlJa.contains("<html lang=\"ja\">"))
        assertTrue(htmlJa.contains("<title>MiniPlayer · PC LAN Web スタジオ</title>"))
        assertTrue(htmlJa.contains("オーディオ試聴"))
        assertTrue(htmlJa.contains("ワイヤレス転送・フォルダ"))
        assertFalse(htmlJa.contains("AUDIO PREVIEW"))
        assertFalse(htmlJa.contains("标题 (Title)"))
        assertTrue(htmlJa.contains("return esc(jsEsc);"))
        assertTrue(htmlJa.contains("syncUrlToken();"))
    }

    @Test
    fun i18nPackageMaintainsPureJvmInvariant() {
        val repoRoot = locateRepoRoot()
        val i18nDir = File(repoRoot, "app/src/main/java/com/miniplayer/app/core/i18n")
        assertTrue(i18nDir.isDirectory)
        val androidImports = ArrayList<String>()
        i18nDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { idx, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("import android.") ||
                        trimmed.startsWith("import androidx.") ||
                        trimmed.startsWith("import dalvik.")
                    ) {
                        androidImports.add("${file.name}:${idx + 1}: $trimmed")
                    }
                }
            }
        assertTrue("core.i18n must stay 100% pure JVM:\n${androidImports.joinToString("\n")}", androidImports.isEmpty())
    }

    private fun locateRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (dir != null && !(File(dir, "settings.gradle.kts").isFile && File(dir, "app").isDirectory)) {
            dir = dir.parentFile
        }
        return checkNotNull(dir) { "Could not locate repository root" }
    }
}
