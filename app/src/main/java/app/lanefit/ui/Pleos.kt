package app.lanefit.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Pleos Connect UI Design Guide tokens (document.pleos.ai, Foundations: Colors, Typography).
 * Only the semantic tokens this app uses. Names follow the guide; `regulation_*` is left out on purpose
 * because nothing here carries a legal telltale meaning.
 */
@Immutable
class PleosColors(
    val backgroundPrimary: Color,
    val surfaceBasic: Color,
    val surfaceLow: Color,
    val surfaceHigh: Color,
    val surfaceAccent: Color,
    /** text_dark_primary. Also the base of alpha_dark_N, which is this color at N/10 %. */
    val textPrimary: Color,
    val informativeActive: Color,
    val informativeNegative: Color,
    val buttonSwitchEnabled: Color,
    val switchOff: Color,
) {
    val textSecondary = textPrimary.copy(alpha = 0.84f)
    val textTertiary = textPrimary.copy(alpha = 0.64f)
    val controllerTabBackground = textPrimary.copy(alpha = 0.05f) // alpha_dark_50
    val border = textPrimary.copy(alpha = 0.10f) // alpha_dark_100
    val switchOn get() = informativeActive
}

/** static_dark_100: the one ink that passes 4.5:1 on informative_active in both themes. */
val StaticDark = Color(0xFF131417)

val PleosLight = PleosColors(
    backgroundPrimary = Color(0xFFF7F8FA),
    surfaceBasic = Color.White,
    surfaceLow = Color(0xFFF7F8FA),
    surfaceHigh = Color(0xFFEDEEF2),
    surfaceAccent = Color(0xFFE0E1E6),
    textPrimary = StaticDark,
    informativeActive = Color(0xFF02C265),
    informativeNegative = Color(0xFFFE3D16),
    buttonSwitchEnabled = Color.White,
    switchOff = StaticDark.copy(alpha = 0.30f),
)

val PleosDark = PleosColors(
    backgroundPrimary = Color(0xFF1D1E21),
    surfaceBasic = Color(0xFF1D1E21),
    surfaceLow = StaticDark,
    surfaceHigh = Color(0xFF313236),
    surfaceAccent = Color(0xFF44464E),
    textPrimary = Color.White,
    informativeActive = Color(0xFF00E074),
    informativeNegative = Color(0xFFFF4C28),
    buttonSwitchEnabled = Color.White.copy(alpha = 0.20f),
    switchOff = Color.White.copy(alpha = 0.10f),
)

val LocalPleos = staticCompositionLocalOf { PleosLight }

// ponytail: Asta Sans comes from the head unit (font family "pretendard" in /product/etc/fonts_customization.xml),
// so nothing is bundled. Off a Pleos image this falls back to the system default; bundle the OFL font if that matters.
private val AstaSans = FontFamily(
    Font(DeviceFontFamilyName("pretendard"), FontWeight.W400),
    Font(DeviceFontFamilyName("pretendard"), FontWeight.W600),
    Font(DeviceFontFamilyName("pretendard"), FontWeight.W800),
)

/** Guide type scale in sp. Headline and Title run at 1.3x line height, Body and Label at 1.5x. */
object PleosType {
    val Bold = FontWeight.W600
    val ExtraBold = FontWeight.W800

    private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.W400) =
        TextStyle(fontFamily = AstaSans, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

    val HeadlineL = style(60, 78, ExtraBold)
    val HeadlineS = style(48, 62, ExtraBold)
    val TitleL = style(40, 52, ExtraBold)
    val TitleM = style(36, 47, Bold)
    val TitleS = style(32, 42, Bold)
    val BodyL = style(30, 45)
    val BodyM = style(28, 42)
    val BodyS = style(26, 39)
    val LabelM = style(24, 36)
}
