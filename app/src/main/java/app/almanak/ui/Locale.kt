package app.almanak.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * The UI language is an app setting (en-US by default), independent of the TV's system locale:
 * string resources below this point resolve against [locale].
 */
@Composable
fun ProvideAppLocale(locale: Locale, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val config = remember(baseConfig, locale) {
        Configuration(baseConfig).apply { setLocale(locale) }
    }
    val context = remember(base, config) { base.createConfigurationContext(config) }
    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config, content = content)
}
