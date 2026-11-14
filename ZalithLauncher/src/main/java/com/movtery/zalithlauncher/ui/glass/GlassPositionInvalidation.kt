package com.movtery.zalithlauncher.ui.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.IntSize

/**
 * Moving an existing owned graphics layer need not redraw its display list. Backdrop 2.0.1
 * remembers LayoutCoordinates but doesn't invalidate draw when the same coordinator moves.
 * Keep the card/key/backdrop alive; invalidate only the consumer layer when sampling moves.
 */
internal object GlassPositionInvalidation : ModifierNodeElement<GlassPositionNode>() {
    override fun create() = GlassPositionNode()
    override fun update(node: GlassPositionNode) = Unit
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 0x474C4153
}

internal class GlassPositionNode : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {
    private var lastOrigin = Offset.Unspecified
    private var lastSize = IntSize.Zero

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val origin = coordinates.positionOnScreen()
        if (!origin.x.isFinite() || !origin.y.isFinite()) return
        if (origin != lastOrigin || coordinates.size != lastSize) {
            lastOrigin = origin
            lastSize = coordinates.size
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() = drawContent()
}
