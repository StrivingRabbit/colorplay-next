package io.github.hypercar.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Rounded soft illumination, without a rectangular Material ripple or touch hotspot. */
object GlassPressIndication : IndicationNodeFactory {
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 1491
    override fun create(interactionSource: InteractionSource): DelegatableNode = GlassPressNode(interactionSource)
}

private class GlassPressNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val alpha = Animatable(0f)
    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableSetOf<PressInteraction.Press>()
            source.interactions.collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses.add(interaction)
                    is PressInteraction.Release -> presses.remove(interaction.press)
                    is PressInteraction.Cancel -> presses.remove(interaction.press)
                }
                alpha.animateTo(if (presses.isNotEmpty()) 0.065f else 0f, tween(100))
            }
        }
    }
    override fun ContentDrawScope.draw() {
        drawContent()
        val radius = minOf(24.dp.toPx(), size.minDimension / 2f)
        drawRoundRect(Color.White.copy(alpha = alpha.value), cornerRadius = CornerRadius(radius, radius))
    }
}
