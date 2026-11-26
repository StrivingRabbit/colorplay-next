package io.github.hypercar.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Semantic colors scoped to this composition, including dialogs and inactive tabs. */
@Immutable
data class AppPalette(
    val dark: Boolean,
    val background: Color,
    val panel: Color,
    val outline: Color,
    val accent: Color,
    val secondary: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val text: Color,
    val secondaryText: Color,
    val onAccent: Color,
    val inset: Color,
    val navigation: Color,
    val scrim: Color,
)

val DarkAppPalette = AppPalette(
    true, Color(0xFF080B10), Color(0xB31C2531), Color(0xFF344353),
    Color(0xFF8AE6CF), Color(0xFFA9B9F7), Color(0xFF8BE0AF), Color(0xFFF2C37F),
    Color(0xFFFF9393), Color(0xFFF3F6FA), Color(0xFFA1AEBF), Color(0xFF071812),
    Color(0xFF151E29), Color(0xD91B2532), Color(0xB303060B),
)

val LightAppPalette = AppPalette(
    false, Color(0xFFF3F4F7), Color.White, Color(0xFFDFE2E8),
    Color(0xFF1265E8), Color(0xFF1265E8), Color(0xFF117854), Color(0xFF956000),
    Color(0xFFC9343A), Color(0xFF1C1D22), Color(0xFF606571), Color.White,
    Color(0xFFECEEF3), Color.White, Color(0x99000000),
)

val LocalAppPalette = staticCompositionLocalOf { DarkAppPalette }

// Preserve the existing component API while resolving colors per composition.
val DeepSpace: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.background

val PanelGlass: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.panel

val StrokeGlass: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.outline

val ElectricCyan: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.accent

val NebulaViolet: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.secondary

val MintGreen: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.success

val AmberWarning: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.warning

val CoralError: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.error

val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.text

val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.secondaryText

val InkBlack: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.onAccent

val SurfaceInset: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.inset

val SurfaceNavBar: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.navigation

val ScrimDeepSpace: Color
    @Composable @ReadOnlyComposable get() = LocalAppPalette.current.scrim
