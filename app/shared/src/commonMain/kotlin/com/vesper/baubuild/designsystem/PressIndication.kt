package com.vesper.baubuild.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import kotlinx.coroutines.launch

/**
 * App-wide tap feedback (installed in [BauTheme] as LocalIndication; Material ripples are disabled).
 * Pressed elements shrink slightly with a spring, iOS-style — never a gray overlay / ripple box.
 */
internal object PressScaleIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressScaleNode(interactionSource)
    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x5ca1e
}

private class PressScaleNode(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
    private val scale = Animatable(1f)

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableSetOf<PressInteraction.Press>()
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                }
                val target = if (presses.isEmpty()) 1f else 0.97f
                launch { scale.animateTo(target, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val s = scale.value
        if (s == 1f) drawContent() else scale(s) { this@draw.drawContent() }
    }
}
