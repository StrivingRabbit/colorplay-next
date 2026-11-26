package io.github.hypercar.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import io.github.hypercar.core.LinkState
import io.github.hypercar.ui.theme.AmberWarning
import io.github.hypercar.ui.theme.CoralError
import io.github.hypercar.ui.theme.ElectricCyan
import io.github.hypercar.ui.theme.LocalAppPalette
import io.github.hypercar.ui.theme.MintGreen
import io.github.hypercar.ui.theme.TextPrimary
import io.github.hypercar.ui.theme.TextSecondary

/** Fork-owned floating header. Only its sampled background fades; text remains sharp. */
@Composable
fun GlassStatusStrip(link: LinkState, modifier: Modifier = Modifier) {
    val backdrop = LocalLiquidBackdrop.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val dark = LocalAppPalette.current.dark
    val tint = if (dark) Color(0xFF111822).copy(alpha = 0.12f) else Color.White.copy(alpha = 0.18f)
    val dot = when (link) {
        is LinkState.Connected -> MintGreen
        is LinkState.Negotiating -> ElectricCyan
        is LinkState.Failed -> CoralError
        is LinkState.Stopping -> AmberWarning
        else -> TextSecondary
    }
    val status = when (link) {
        is LinkState.Connected -> link.carName.take(12)
        is LinkState.Negotiating -> "协商中"
        is LinkState.Failed -> "连接失败"
        is LinkState.Stopping -> "断开中"
        else -> "待机"
    }
    Box(modifier.fillMaxWidth().height(topInset + 72.dp)) {
        if (backdrop != null) {
            Box(
                Modifier.matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Black,
                                0.62f to Color.Black,
                                0.82f to Color.Black.copy(alpha = 0.65f),
                                1f to Color.Transparent,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = { blur(14.dp.toPx()) },
                        onDrawSurface = { drawRect(tint) },
                    ),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = topInset).height(48.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(dot, dotSize = 8.dp)
            Spacer(Modifier.width(10.dp))
            Text("ColorPlay Next", style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 0.3.sp), color = TextPrimary)
            Spacer(Modifier.weight(1f))
            Text(status, style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1)
        }
    }
}
