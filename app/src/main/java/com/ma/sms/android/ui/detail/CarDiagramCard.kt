package com.ma.sms.android.ui.detail

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ma.sms.android.R
import com.ma.sms.android.data.model.DocumentSinistre
import java.io.File

// Positions des hotspots en fractions (x, y) du conteneur
private val anglePositions = mapOf(
    "Face avant"      to Pair(0.50f, 0.03f),
    "Avant droit"     to Pair(0.87f, 0.16f),
    "Avant gauche"    to Pair(0.13f, 0.16f),
    "Latérale droite" to Pair(0.96f, 0.50f),
    "Latérale gauche" to Pair(0.04f, 0.50f),
    "Face arrière"    to Pair(0.50f, 0.95f),
    "Arrière droit"   to Pair(0.87f, 0.82f),
    "Arrière gauche"  to Pair(0.13f, 0.82f),
    "Tableau de bord" to Pair(0.28f, 0.35f),
    "N°CHAS"          to Pair(0.72f, 0.35f),
    "Intérieur"       to Pair(0.50f, 0.62f)
)

private val HOTSPOT_SIZE = 36.dp

@Composable
fun CarDiagramCard(
    etat: String?,
    documents: List<DocumentSinistre>,
    pendingPhotos: List<PendingPhoto>,
    onTakePhoto: (String) -> Unit,
    onViewDocument: (DocumentSinistre) -> Unit = {},
    onViewPendingPhoto: (File) -> Unit = {}
) {
    val doneCount = VEHICLE_ANGLES.filter { it.required }.count { isAngleUploadedForState(it, etat, documents) }
    val totalRequired = VEHICLE_ANGLES.count { it.required }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Photos véhicule",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (doneCount == totalRequired) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        "$doneCount / $totalRequired",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (doneCount == totalRequired) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Text(
                "Appuyez sur un point pour prendre la photo de cet angle, ou sur son nom pour la consulter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Diagramme voiture avec hotspots
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                val containerWidth = maxWidth
                // Ratio 120:240 = 0.5
                val containerHeight = containerWidth * (240f / 120f)
                val halfHotspot = HOTSPOT_SIZE / 2

                Box(modifier = Modifier.size(containerWidth, containerHeight)) {

                    // Image voiture centrée
                    Image(
                        painter = painterResource(id = R.drawable.ic_car_top_view),
                        contentDescription = "Vue dessus véhicule",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    // Hotspots
                    VEHICLE_ANGLES.forEach { angle ->
                        val pos = anglePositions[angle.label] ?: return@forEach
                        val docType = angleDocTypeForState(angle, etat)
                        val isUploaded = isAngleUploadedForState(angle, etat, documents)
                        val pendingFile = pendingPhotos.firstOrNull { it.docType == docType }?.file
                        val isPending = pendingFile != null

                        val x = containerWidth * pos.first - halfHotspot
                        val y = containerHeight * pos.second - halfHotspot

                        AngleHotspot(
                            modifier = Modifier.offset(x = x, y = y),
                            angle = angle,
                            isUploaded = isUploaded,
                            isPending = isPending,
                            onClick = { onTakePhoto(docType) },
                            onView = {
                                when {
                                    pendingFile != null -> onViewPendingPhoto(pendingFile)
                                    isUploaded -> findAngleDocument(angle, etat, documents)?.let { onViewDocument(it) }
                                }
                            }
                        )
                    }
                }
            }

            // Légende
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LegendItem(color = MaterialTheme.colorScheme.primary, label = "Envoyée")
                LegendItem(color = MaterialTheme.colorScheme.secondary, label = "En attente")
                LegendItem(color = MaterialTheme.colorScheme.error, label = "Manquante")
                LegendItem(color = MaterialTheme.colorScheme.onSurfaceVariant, label = "Optionnel")
            }
        }
    }
}

@Composable
private fun AngleHotspot(
    modifier: Modifier = Modifier,
    angle: VehicleAngle,
    isUploaded: Boolean,
    isPending: Boolean,
    onClick: () -> Unit,
    onView: () -> Unit = {}
) {
    val hasPhoto = isUploaded || isPending
    val bgColor = when {
        isUploaded -> MaterialTheme.colorScheme.primary
        isPending  -> MaterialTheme.colorScheme.secondary
        angle.required -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when {
        isUploaded -> Icons.Default.Check
        isPending  -> Icons.Default.CloudUpload
        else       -> Icons.Default.CameraAlt
    }

    Column(
        modifier = modifier.width(HOTSPOT_SIZE + 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(HOTSPOT_SIZE),
            shape = CircleShape,
            color = bgColor,
            shadowElevation = 3.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = angle.label,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Text(
            text = angle.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            textAlign = TextAlign.Center,
            color = if (hasPhoto) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (hasPhoto) androidx.compose.ui.text.style.TextDecoration.Underline else null,
            maxLines = 1,
            modifier = Modifier
                .width(HOTSPOT_SIZE + 20.dp)
                .then(if (hasPhoto) Modifier.clickable(onClick = onView) else Modifier)
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(8.dp)) {}
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
