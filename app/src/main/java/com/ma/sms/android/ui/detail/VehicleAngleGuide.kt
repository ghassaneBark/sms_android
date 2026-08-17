package com.ma.sms.android.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ma.sms.android.R

/**
 * Mini-carte "vue de dessus" affichee en incrustation (coin de l'ecran, sans recouvrir l'apercu
 * camera) pour indiquer clairement quel cote du vehicule photographier : reprend le meme visuel
 * vectoriel professionnel deja utilise dans "Photos vehicule" (CarDiagramCard, R.drawable.ic_car_top_view),
 * avec la zone concernee surlignee. Beaucoup plus lisible qu'une silhouette en perspective, et
 * coherent avec le reste de l'application.
 */

private data class GuideZone(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

// Coordonnees dans le meme repere que le drawable (viewportWidth=120, viewportHeight=240), pour
// un alignement precis avec le capot/coffre/portieres/roues dessines.
private fun resolveGuideZone(label: String): GuideZone? = when (label) {
    "Face avant" -> GuideZone(8f, 3f, 112f, 50f)
    "Face arrière" -> GuideZone(8f, 192f, 112f, 237f)
    "Latérale droite" -> GuideZone(85f, 45f, 120f, 195f)
    "Latérale gauche" -> GuideZone(0f, 45f, 35f, 195f)
    "Avant droit" -> GuideZone(70f, 3f, 120f, 95f)
    "Avant gauche" -> GuideZone(0f, 3f, 50f, 95f)
    "Arrière droit" -> GuideZone(70f, 145f, 120f, 237f)
    "Arrière gauche" -> GuideZone(0f, 145f, 50f, 237f)
    // Zones centrees sur les memes positions que les hotspots de CarDiagramCard (0.28,0.35),
    // (0.72,0.35) et (0.50,0.62) rapportees au repere du drawable (120x240), pour rester coherent.
    "Tableau de bord" -> GuideZone(16f, 66f, 52f, 102f)
    "N°CHAS" -> GuideZone(68f, 66f, 104f, 102f)
    "Intérieur" -> GuideZone(42f, 122f, 78f, 176f)
    else -> null
}

private const val CAR_VIEWPORT_W = 120f
private const val CAR_VIEWPORT_H = 240f
private val CARD_WIDTH = 88.dp

@Composable
fun VehicleAngleGuideOverlay(angleLabel: String, modifier: Modifier = Modifier) {
    val zone = resolveGuideZone(angleLabel) ?: return

    Box(
        modifier = modifier
            .width(CARD_WIDTH)
            .height(CARD_WIDTH * (CAR_VIEWPORT_H / CAR_VIEWPORT_W))
            .background(Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.ic_car_top_view),
                contentDescription = "Vue de dessus du véhicule",
                modifier = Modifier.fillMaxSize()
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scaleX = size.width / CAR_VIEWPORT_W
                val scaleY = size.height / CAR_VIEWPORT_H
                val topLeft = Offset(zone.x0 * scaleX, zone.y0 * scaleY)
                val zoneSize = Size((zone.x1 - zone.x0) * scaleX, (zone.y1 - zone.y0) * scaleY)
                val fillColor = Color(0xFFFFC107).copy(alpha = 0.55f)
                val strokeColor = Color(0xFFFFC107)
                drawRoundRect(
                    color = fillColor,
                    topLeft = topLeft,
                    size = zoneSize,
                    cornerRadius = CornerRadius(6f * scaleX, 6f * scaleY)
                )
                drawRoundRect(
                    color = strokeColor,
                    topLeft = topLeft,
                    size = zoneSize,
                    cornerRadius = CornerRadius(6f * scaleX, 6f * scaleY),
                    style = Stroke(width = 3f)
                )
            }
        }
    }
}
