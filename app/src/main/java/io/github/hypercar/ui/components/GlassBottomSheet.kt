package io.github.hypercar.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import io.github.hypercar.ui.theme.LocalAppPalette

/** 根布局仅录制页面内容；弹层不可录进自己采样的背景。 */
val LocalOverlayBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = LocalOverlayBackdrop.current ?: LocalLiquidBackdrop.current
    val sourceView = LocalView.current
    val dark = LocalAppPalette.current.dark
    val shape = RoundedCornerShape(32.dp)
    val tint = if (dark) Color(0xFF142238).copy(alpha = 0.12f)
        else Color.White.copy(alpha = 0.18f)
    val scrim = Color.Black.copy(alpha = 0.22f)
    val sheetBackdrop = rememberLayerBackdrop()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = shape,
        containerColor = Color.Transparent,
        contentColor = io.github.hypercar.ui.theme.TextPrimary,
        tonalElevation = 0.dp,
        scrimColor = scrim,
        dragHandle = null,
    ) {
        val sheetView = LocalView.current
        val sourceScreen = remember(sourceView) { IntArray(2) }
        val sourceWindow = remember(sourceView) { IntArray(2) }
        val sheetScreen = remember(sheetView) { IntArray(2) }
        val sheetWindow = remember(sheetView) { IntArray(2) }
        val glassModifier = if (backdrop != null) {
            Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                highlight = null,
                shadow = null,
                innerShadow = null,
                effects = {
                    vibrancy()
                    blur(6.dp.toPx())
                    lens(24.dp.toPx(), 36.dp.toPx(), chromaticAberration = false)
                },
                exportedBackdrop = sheetBackdrop,
                onDrawBackdrop = { drawSample ->
                    // LayerBackdrop 跨根布局回退到窗口坐标；补上两个窗口在屏幕上的原点差。
                    sourceView.getLocationOnScreen(sourceScreen)
                    sourceView.getLocationInWindow(sourceWindow)
                    sheetView.getLocationOnScreen(sheetScreen)
                    sheetView.getLocationInWindow(sheetWindow)
                    val dx = (sourceScreen[0] - sourceWindow[0]) - (sheetScreen[0] - sheetWindow[0])
                    val dy = (sourceScreen[1] - sourceWindow[1]) - (sheetScreen[1] - sheetWindow[1])
                    translate(dx.toFloat(), dy.toFloat()) { drawSample() }
                    // 页面采样不含 ModalBottomSheet 的遮罩，补齐相同的遮暗程度。
                    drawRect(scrim)
                },
                onDrawSurface = { drawRect(tint) },
            )
        } else {
            Modifier.background(tint, shape)
        }
        Column(glassModifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(width = 36.dp, height = 4.dp)
                        .background(
                            if (dark) Color.White.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.18f),
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
            // 导出层只包含面板玻璃，不包含其子内容，避免玻璃按钮递归采样。
            CompositionLocalProvider(LocalLiquidBackdrop provides if (backdrop != null) sheetBackdrop else null) {
                Column(
                    Modifier.fillMaxWidth().padding(contentPadding),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
            }
        }
    }
}
