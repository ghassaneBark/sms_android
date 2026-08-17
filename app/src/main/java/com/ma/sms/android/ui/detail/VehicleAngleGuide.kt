package com.ma.sms.android.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin

/**
 * Gabarit (silhouette du vehicule) affiche en surimpression sur l'apercu camera pour les prises
 * exterieures obligatoires : plus pratique qu'un simple libelle pour cadrer correctement l'angle
 * demande. Les prises non exterieures (tableau de bord, N°CHAS, interieur) n'ont pas de gabarit.
 */

private enum class GuideShape { FRONT, REAR, SIDE, THREE_QUARTER }
private data class GuideSpec(val shape: GuideShape, val mirror: Boolean)

private fun resolveGuideSpec(label: String): GuideSpec? = when (label) {
    "Face avant" -> GuideSpec(GuideShape.FRONT, mirror = false)
    "Face arrière" -> GuideSpec(GuideShape.REAR, mirror = false)
    "Latérale droite" -> GuideSpec(GuideShape.SIDE, mirror = false)
    "Latérale gauche" -> GuideSpec(GuideShape.SIDE, mirror = true)
    "Avant droit" -> GuideSpec(GuideShape.THREE_QUARTER, mirror = false)
    "Avant gauche" -> GuideSpec(GuideShape.THREE_QUARTER, mirror = true)
    "Arrière droit" -> GuideSpec(GuideShape.THREE_QUARTER, mirror = true)
    "Arrière gauche" -> GuideSpec(GuideShape.THREE_QUARTER, mirror = false)
    else -> null
}

private const val FRONT_BOX_W = 200f
private const val FRONT_BOX_H = 100f
private const val SIDE_BOX_W = 220f
private const val SIDE_BOX_H = 100f
private const val TQ_BOX_W = 220f
private const val TQ_BOX_H = 120f

private fun boxSizeFor(shape: GuideShape): Size = when (shape) {
    GuideShape.FRONT, GuideShape.REAR -> Size(FRONT_BOX_W, FRONT_BOX_H)
    GuideShape.SIDE -> Size(SIDE_BOX_W, SIDE_BOX_H)
    GuideShape.THREE_QUARTER -> Size(TQ_BOX_W, TQ_BOX_H)
}

// Silhouette symetrique (face avant / face arriere), le detail (phares/calandre ou feux/coffre)
// est ajoute a part selon le cas.
private fun buildBodyOutlineFrontRear(): Path = Path().apply {
    moveTo(82f, 8f)
    lineTo(118f, 8f)
    lineTo(145f, 30f)
    lineTo(170f, 60f)
    lineTo(176f, 78f)
    lineTo(172f, 92f)
    lineTo(28f, 92f)
    lineTo(24f, 78f)
    lineTo(30f, 60f)
    lineTo(55f, 30f)
    close()
}

private fun buildBodyOutlineSide(): Path = Path().apply {
    moveTo(15f, 78f)
    lineTo(18f, 58f)
    lineTo(35f, 50f)
    lineTo(72f, 18f)
    lineTo(118f, 18f)
    lineTo(145f, 42f)
    lineTo(178f, 52f)
    lineTo(198f, 58f)
    lineTo(202f, 78f)
    close()
}

private fun buildBodyOutlineThreeQuarter(): Path = Path().apply {
    moveTo(50f, 88f)
    lineTo(48f, 62f)
    lineTo(68f, 38f)
    lineTo(95f, 18f)
    lineTo(145f, 18f)
    lineTo(178f, 35f)
    lineTo(202f, 58f)
    lineTo(208f, 72f)
    lineTo(206f, 90f)
    close()
}

private data class Circle(val cx: Float, val cy: Float, val r: Float)

private fun mirroredX(x: Float, boxWidth: Float, mirror: Boolean) = if (mirror) boxWidth - x else x

private fun mirroredPath(path: Path, boxWidth: Float, mirror: Boolean): Path {
    if (!mirror) return path
    val matrix = Matrix().apply {
        scale(x = -1f, y = 1f)
        translate(x = -boxWidth, y = 0f)
    }
    val out = Path()
    out.addPath(path)
    out.transform(matrix)
    return out
}

@Composable
fun VehicleAngleGuideOverlay(angleLabel: String, modifier: Modifier = Modifier) {
    val spec = resolveGuideSpec(angleLabel) ?: return
    val box = boxSizeFor(spec.shape)

    Canvas(modifier = modifier) {
        val margin = 0.16f
        val availableW = size.width * (1f - 2 * margin)
        val availableH = size.height * (1f - 2 * margin)
        val scaleFactor = minOf(availableW / box.width, availableH / box.height)
        val drawW = box.width * scaleFactor
        val drawH = box.height * scaleFactor
        val offsetX = (size.width - drawW) / 2f
        val offsetY = (size.height - drawH) / 2f

        fun toCanvas(x: Float, y: Float) = Offset(offsetX + x * scaleFactor, offsetY + y * scaleFactor)

        val outline = when (spec.shape) {
            GuideShape.FRONT, GuideShape.REAR -> buildBodyOutlineFrontRear()
            GuideShape.SIDE -> mirroredPath(buildBodyOutlineSide(), box.width, spec.mirror)
            GuideShape.THREE_QUARTER -> mirroredPath(buildBodyOutlineThreeQuarter(), box.width, spec.mirror)
        }

        val wheels: List<Circle> = when (spec.shape) {
            GuideShape.SIDE -> listOf(
                Circle(mirroredX(50f, box.width, spec.mirror), 78f, 15f),
                Circle(mirroredX(168f, box.width, spec.mirror), 78f, 15f)
            )
            GuideShape.THREE_QUARTER -> listOf(
                Circle(mirroredX(185f, box.width, spec.mirror), 90f, 18f),
                Circle(mirroredX(65f, box.width, spec.mirror), 90f, 11f)
            )
            else -> emptyList()
        }

        val shadowStroke = Stroke(width = 7f * scaleFactor, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val mainStroke = Stroke(width = 3.5f * scaleFactor, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val shadowColor = Color.Black.copy(alpha = 0.35f)
        val mainColor = Color.White.copy(alpha = 0.9f)

        fun drawScaledPath(path: Path) {
            val scaledMatrix = Matrix().apply {
                scale(x = scaleFactor, y = scaleFactor)
                translate(x = offsetX / scaleFactor, y = offsetY / scaleFactor)
            }
            val scaled = Path().apply { addPath(path); transform(scaledMatrix) }
            drawPath(scaled, color = shadowColor, style = shadowStroke)
            drawPath(scaled, color = mainColor, style = mainStroke)
        }

        drawScaledPath(outline)

        wheels.forEach { c ->
            val center = toCanvas(c.cx, c.cy)
            val radius = c.r * scaleFactor
            drawCircle(shadowColor, radius, center, style = shadowStroke)
            drawCircle(mainColor, radius, center, style = mainStroke)
        }

        if (spec.shape == GuideShape.FRONT) {
            val leftLight = Path().apply { addOval(androidx.compose.ui.geometry.Rect(toCanvas(36f, 59f), toCanvas(64f, 77f))) }
            val rightLight = Path().apply { addOval(androidx.compose.ui.geometry.Rect(toCanvas(136f, 59f), toCanvas(164f, 77f))) }
            drawPath(leftLight, color = mainColor, style = mainStroke)
            drawPath(rightLight, color = mainColor, style = mainStroke)
            val grille = Path().apply {
                addRect(androidx.compose.ui.geometry.Rect(toCanvas(82f, 75f), toCanvas(118f, 90f)))
            }
            drawPath(grille, color = mainColor, style = mainStroke)
        }
        if (spec.shape == GuideShape.REAR) {
            val leftTail = Path().apply { addRect(androidx.compose.ui.geometry.Rect(toCanvas(38f, 55f), toCanvas(58f, 80f))) }
            val rightTail = Path().apply { addRect(androidx.compose.ui.geometry.Rect(toCanvas(142f, 55f), toCanvas(162f, 80f))) }
            drawPath(leftTail, color = mainColor, style = mainStroke)
            drawPath(rightTail, color = mainColor, style = mainStroke)
            drawLine(mainColor, toCanvas(60f, 85f), toCanvas(140f, 85f), strokeWidth = 3f * scaleFactor, cap = StrokeCap.Round)
        }
    }
}
