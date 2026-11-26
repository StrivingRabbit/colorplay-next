package io.github.hypercar.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.state.ToggleableState
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hypercar.ui.theme.DeepSpace
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.LocalGlass

/** 由根布局提供已录制的背景；玻璃组件不得录制进自己正在采样的同一层。 */
val LocalLiquidBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** 仅对采样背景施加模糊和 AGSL 折射，后续绘制的文字、图标保持清晰。 */
@Composable
fun Modifier.liquidGlassSurface(
    shape: CornerBasedShape,
    tint: Color,
    blurRadius: Dp = 4.dp,
    refractionHeight: Dp = 14.dp,
    refractionAmount: Dp = 20.dp,
): Modifier {
    val backdrop = LocalLiquidBackdrop.current
    return if (backdrop != null) {
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            // Explicit weak directional reflection, not the library's full-strength default ring.
            highlight = { Highlight(width = 0.6.dp, blurRadius = 0.8.dp, alpha = 0.38f,
                style = HighlightStyle.Default(intensity = 0.24f, angle = 60f, falloff = 2f)) },
            shadow = { Shadow(radius = 12.dp, color = Color.Black.copy(alpha = 0.24f)) },
            innerShadow = null,
            effects = {
                vibrancy()
                blur(blurRadius.toPx())
                lens(
                    refractionHeight = refractionHeight.toPx(),
                    refractionAmount = refractionAmount.toPx(),
                    chromaticAberration = false,
                )
            },
            onDrawSurface = { drawRect(tint) },
        )
    } else {
        // 独立预览未提供采样层时，仍保留可读的透明表面。
        background(tint, shape)
    }
}

/** 折射主体 + 受控方向反光及表面染色；glow 参数仅为兼容保留，不绘制外辉光。 */
@Composable
fun Modifier.glassPanel(
    cornerRadius: Dp = LocalGlass.current.cornerRadius,
    glow: Boolean = false,
    glowColor: Color = LocalGlass.current.glowColor,
): Modifier {
    val dark = io.github.hypercar.ui.theme.LocalAppPalette.current.dark
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .liquidGlassSurface(
            shape = shape,
            tint = if (dark) Color(0xFF8BA5BB).copy(alpha = 0.14f) else Color.White.copy(alpha = 0.48f),
        )
        .clip(shape)
}

/** 背景折射卡片，内容统一 20dp 内边距。 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = LocalGlass.current.cornerRadius,
    glow: Boolean = false,
    glowColor: Color = LocalGlass.current.glowColor,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.glassPanel(cornerRadius = cornerRadius, glow = glow, glowColor = glowColor)
            .padding(contentPadding),
        content = content,
    )
}

/** 章节小标题（设置页 / 首页区块）。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = ElectricCyan.copy(alpha = 0.85f),
        modifier = modifier.padding(start = 4.dp, top = 4.dp),
    )
}

/**
 * 胶囊按钮（主按钮全宽 52dp，最小触达 44dp）：
 * [filled] = true 使用强调色玻璃；false 使用轻染色玻璃（停止 / 断开等次要动作）。
 * 深色模式用强调色文字，浅色强调按钮用深色文字，保证折射背景上的可读性。
 */
@Composable
fun CapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
    containerColor: Color = ElectricCyan,
    contentColor: Color = Color.Unspecified,
    borderColor: Color = ElectricCyan, // 兼容现有调用，不再绘制描边。
    leadingIcon: ImageVector? = null,
    /**
     * 左右内边距。默认 20dp；**四等分一行的窄按钮要传小值**
     * （20dp × 2 + 图标 + 两个汉字会超宽，系统把最后一个字吞掉，2026-10-25 诊断页截图）。
     */
    horizontalPadding: Dp = 20.dp,
) {
    val shape = RoundedCornerShape(24.dp)
    val resolvedContent = if (contentColor == Color.Unspecified) {
        if (filled && !io.github.hypercar.ui.theme.LocalAppPalette.current.dark) DeepSpace else containerColor
    } else {
        contentColor
    }
    val tint = resolvedContent.copy(alpha = if (enabled) 1f else 0.4f)
    val dark = io.github.hypercar.ui.theme.LocalAppPalette.current.dark
    val opacity = if (enabled) 1f else 0.35f
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .liquidGlassSurface(
                shape = shape,
                tint = if (filled) containerColor.copy(alpha = 0.16f * opacity)
                    else Color.White.copy(alpha = (if (dark) 0.04f else 0.10f) * opacity),
                blurRadius = 4.dp,
                refractionHeight = 6.dp,
                refractionAmount = 8.dp,
            )
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = horizontalPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = tint,
            maxLines = 1,
        )
    }
}
/** 轨道与滑块分别折射背景，保留 Switch 无障碍语义及最小触摸区域。 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: androidx.compose.material3.SwitchColors = androidx.compose.material3.SwitchDefaults.colors(),
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "switch-press")
    val thumbOffset by animateDpAsState(if (checked) 26.dp else 4.dp, label = "玻璃开关滑块")
    val trackColor = if (enabled) {
        if (checked) colors.checkedTrackColor else colors.uncheckedTrackColor
    } else {
        if (checked) colors.disabledCheckedTrackColor else colors.disabledUncheckedTrackColor
    }
    val thumbColor = if (enabled) {
        if (checked) colors.checkedThumbColor else colors.uncheckedThumbColor
    } else {
        if (checked) colors.disabledCheckedThumbColor else colors.disabledUncheckedThumbColor
    }
    Box(
        modifier = modifier
            .then(
                if (onCheckedChange != null) Modifier.toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    interactionSource = interaction,
                    indication = null,
                    onValueChange = onCheckedChange,
                ) else Modifier.semantics {
                    role = Role.Switch
                    toggleableState = ToggleableState(checked)
                    if (!enabled) disabled()
                },
            )
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(width = 58.dp, height = 34.dp)
                .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                .liquidGlassSurface(
                    shape = RoundedCornerShape(50),
                    tint = trackColor.copy(alpha = if (!enabled) 0.16f else if (checked) 0.78f else 0.20f),
                    blurRadius = 2.dp,
                    refractionHeight = 6.dp,
                    refractionAmount = 8.dp,
                ),
        ) {
            Box(
                Modifier.align(Alignment.CenterStart)
                    .offset(x = thumbOffset)
                    .size(28.dp)
                    .liquidGlassSurface(
                        shape = RoundedCornerShape(50),
                        tint = (if (checked) Color(0xFFF4FCFF) else Color(0xFFC1CFDC)).copy(alpha = if (enabled) 0.96f else 0.44f),
                        blurRadius = 1.dp,
                        refractionHeight = 5.dp,
                        refractionAmount = 6.dp,
                    ),
            )
        }
    }
}

/** 状态小圆点（统计芯片 / 状态条用）。 */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, dotSize: Dp = 8.dp) {
    Canvas(modifier = modifier.size(dotSize)) {
        drawCircle(color = color.copy(alpha = 0.22f))
        drawCircle(color = color, radius = dotSize.toPx() / 3.2f)
    }
}
