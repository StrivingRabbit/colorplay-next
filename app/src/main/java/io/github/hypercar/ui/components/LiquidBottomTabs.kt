package io.github.hypercar.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.shadow.InnerShadow
import androidx.compose.ui.unit.DpOffset
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.LocalAppPalette
import io.github.hypercar.ui.theme.TextSecondary
import kotlin.math.roundToInt

/** Android-only adaptation of the Backdrop glass-bar / liquid-tabs design.
 * The bar and spring-driven indicator refract recorded page pixels, with no stroke or default highlight.
 * Upstream reference: Kyant0/AndroidLiquidGlass catalog/components/LiquidBottomTabs.kt (Apache-2.0).
 */
data class LiquidTabItem(val title: String, val icon: ImageVector)

@Composable
fun LiquidBottomTabs(
    items: List<LiquidTabItem>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    require(items.isNotEmpty())
    val dark = LocalAppPalette.current.dark
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val latestSelect by rememberUpdatedState(onSelected)
    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    val safeIndex = selectedIndex.coerceIn(items.indices)
    val target = if (dragging) dragIndex else safeIndex.toFloat()
    val position by animateFloatAsState(target, spring(dampingRatio = 0.78f, stiffness = 420f), label = "liquid-tab-position")
    val stretch by animateFloatAsState(if (dragging) 1.04f else 1f, spring(), label = "liquid-tab-press")
    val shape = RoundedCornerShape(50)
    val barBackdrop = rememberLayerBackdrop()
    val indicatorBackdrop = rememberCombinedBackdrop(backdrop, barBackdrop)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(72.dp)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(4.dp.toPx())
                    lens(28.dp.toPx(), 34.dp.toPx(), depthEffect = true)
                },
                highlight = { Highlight(width = 1.3.dp, blurRadius = 0.8.dp, alpha = if (dark) 0.70f else 0.60f,
                    style = HighlightStyle.Default(intensity = 0.42f, angle = 55f, falloff = 2.4f)) },
                shadow = { Shadow(radius = if (dark) 18.dp else 10.dp,
                    color = Color.Black.copy(alpha = if (dark) 0.30f else 0.09f)) },
                innerShadow = { InnerShadow(radius = 4.dp, offset = DpOffset(0.dp, 1.dp),
                    color = Color.Black.copy(alpha = if (dark) 0.18f else 0.07f)) },
                exportedBackdrop = barBackdrop,
                onDrawSurface = { drawRect(if (dark) Color(0xFF7489A5).copy(alpha = 0.12f) else Color.White.copy(alpha = 0.26f)) },
            )
            .padding(4.dp)
            .selectableGroup(),
    ) {
        val tabWidth = maxWidth / items.size
        val tabWidthPx = with(LocalDensity.current) { tabWidth.toPx() }
        Box(
            Modifier.offset(x = tabWidth * position)
                .width(tabWidth).fillMaxHeight()
                .graphicsLayer { scaleX = stretch; scaleY = stretch }
                .drawBackdrop(
                    backdrop = indicatorBackdrop,
                    shape = { shape },
                    effects = {
                        vibrancy()
                        blur(1.dp.toPx())
                        lens(16.dp.toPx(), 28.dp.toPx(), chromaticAberration = true)
                    },
                    highlight = null,
                    shadow = null,
                    innerShadow = null,
                    onDrawSurface = { drawRect(if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFF71849D).copy(alpha = 0.12f)) },
                ),
        )
        Row(
            Modifier.fillMaxSize().pointerInput(items.size, tabWidthPx, ltr, safeIndex) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        val index = offset.x / tabWidthPx - 0.5f
                        dragIndex = (if (ltr) index else items.lastIndex - index).coerceIn(0f, items.lastIndex.toFloat())
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        dragIndex = (dragIndex + delta / tabWidthPx * if (ltr) 1f else -1f).coerceIn(0f, items.lastIndex.toFloat())
                    },
                    onDragEnd = {
                        dragging = false
                        latestSelect(dragIndex.roundToInt().coerceIn(items.indices))
                    },
                    onDragCancel = { dragging = false },
                )
            },
        ) {
            items.forEachIndexed { index, item ->
                val active = index == safeIndex
                val interaction = remember { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                val itemScale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(), label = "tab-press-$index")
                val tint = if (active) ElectricCyan else TextSecondary
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .graphicsLayer { scaleX = itemScale; scaleY = itemScale }
                        .clip(shape)
                        .selectable(selected = active, role = Role.Tab, interactionSource = interaction,
                            indication = null, onClick = { latestSelect(index) }),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(23.dp))
                    Text(item.title, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
