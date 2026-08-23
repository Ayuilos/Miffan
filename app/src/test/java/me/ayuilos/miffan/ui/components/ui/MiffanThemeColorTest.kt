package me.ayuilos.miffan.ui.components.ui

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class MiffanThemeColorTest {
    @Test
    fun appThemeColorsUseSemanticMaterialRoles() {
        val scheme = lightColorScheme(
            primary = Color(0xFF102030),
            onPrimary = Color(0xFFF0E0D0),
            primaryContainer = Color(0xFF405060),
            secondary = Color(0xFF708090),
            secondaryContainer = Color(0xFFA0B0C0),
            onSecondaryContainer = Color(0xFF010203),
        )

        assertEquals(
            MiffanColors(
                bowl = scheme.primary,
                rim = scheme.secondary,
                rice = scheme.primaryContainer,
                face = scheme.onPrimary,
                cueSurface = scheme.secondaryContainer,
                cueInk = scheme.onSecondaryContainer,
            ),
            scheme.miffanColors(),
        )
    }
}
