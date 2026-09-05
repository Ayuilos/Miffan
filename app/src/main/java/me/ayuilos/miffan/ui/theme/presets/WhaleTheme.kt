package me.ayuilos.miffan.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import me.ayuilos.miffan.ui.theme.PresetTheme

const val WHALE_THEME_ID = "whale_girl"

/** Clear blue by day, deep ocean by night; text keeps strong contrast in both. */
val WhaleThemePreset by lazy {
    PresetTheme(
        id = WHALE_THEME_ID,
        name = { Text("蓝色大肥鱼") },
        standardLight = lightColorScheme(
            primary = Color(0xFF285AD5),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDCE6FF),
            onPrimaryContainer = Color(0xFF123A88),
            secondary = Color(0xFF49617D),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFD9E8FC),
            onSecondaryContainer = Color(0xFF29425D),
            tertiary = Color(0xFF62558F),
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFE9DDFF),
            onTertiaryContainer = Color(0xFF453772),
            background = Color(0xFFF8FAFF),
            onBackground = Color(0xFF192334),
            surface = Color(0xFFF8FAFF),
            onSurface = Color(0xFF192334),
            surfaceVariant = Color(0xFFE0E7F3),
            onSurfaceVariant = Color(0xFF444F62),
            outline = Color(0xFF737F92),
            outlineVariant = Color(0xFFC3CDDF),
            inverseSurface = Color(0xFF2D3749),
            inverseOnSurface = Color(0xFFEDF2FF),
            inversePrimary = Color(0xFFADC6FF),
            surfaceDim = Color(0xFFD7DFEE),
            surfaceBright = Color(0xFFF8FAFF),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color(0xFFF0F5FF),
            surfaceContainer = Color(0xFFEAF0FC),
            surfaceContainerHigh = Color(0xFFE4EBF8),
            surfaceContainerHighest = Color(0xFFDEE5F2),
        ),
        standardDark = darkColorScheme(
            primary = Color(0xFFADC6FF),
            onPrimary = Color(0xFF082D70),
            primaryContainer = Color(0xFF20468F),
            onPrimaryContainer = Color(0xFFDCE6FF),
            secondary = Color(0xFFB0C9E7),
            onSecondary = Color(0xFF18324C),
            secondaryContainer = Color(0xFF304A65),
            onSecondaryContainer = Color(0xFFD9E8FC),
            tertiary = Color(0xFFCDBDF7),
            onTertiary = Color(0xFF352658),
            tertiaryContainer = Color(0xFF4C3E70),
            onTertiaryContainer = Color(0xFFE9DDFF),
            background = Color(0xFF0D1526),
            onBackground = Color(0xFFE0E7F5),
            surface = Color(0xFF0D1526),
            onSurface = Color(0xFFE0E7F5),
            surfaceVariant = Color(0xFF3F4B61),
            onSurfaceVariant = Color(0xFFC2CDDF),
            outline = Color(0xFF8D9AB0),
            outlineVariant = Color(0xFF3F4B61),
            inverseSurface = Color(0xFFE0E7F5),
            inverseOnSurface = Color(0xFF283245),
            inversePrimary = Color(0xFF285AD5),
            surfaceDim = Color(0xFF0D1526),
            surfaceBright = Color(0xFF333F55),
            surfaceContainerLowest = Color(0xFF080F1D),
            surfaceContainerLow = Color(0xFF141F32),
            surfaceContainer = Color(0xFF192538),
            surfaceContainerHigh = Color(0xFF243045),
            surfaceContainerHighest = Color(0xFF2E3B50),
        ),
    )
}
