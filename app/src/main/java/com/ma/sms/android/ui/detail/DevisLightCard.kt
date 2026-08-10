package com.ma.sms.android.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ma.sms.android.data.model.Devis

@Composable
fun DevisLightCard(devis: Devis, onViewPdf: () -> Unit = {}) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // En-tête
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Accord sur devis",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = devis.reference ?: "—",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(onClick = onViewPdf, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.PictureAsPdf,
                            contentDescription = "Voir le PDF de l'accord sur devis",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // En-tête des colonnes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                HeaderCell("Élément", weight = 2.2f)
                HeaderCell("Pos.", weight = 0.9f)
                HeaderCell("Type", weight = 0.9f)
                HeaderCell("SR", weight = 0.6f)
            }

            if (devis.fournitures.isEmpty()) {
                Text(
                    text = "Aucune pièce",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                devis.fournitures.forEachIndexed { index, f ->
                    val bg = if (index % 2 == 0) MaterialTheme.colorScheme.surface
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bg)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        BodyCell(text = f.element ?: "-", weight = 2.2f)
                        BodyCell(
                            text = listOfNotNull(f.position1?.takeIf { it.isNotBlank() }, f.position2?.takeIf { it.isNotBlank() })
                                .joinToString("-").ifBlank { "-" },
                            weight = 0.9f
                        )
                        BodyCell(text = f.type ?: "-", weight = 0.9f)
                        BodyCell(
                            text = if (f.sousReserve) "Oui" else "Non",
                            weight = 0.6f,
                            color = if (f.sousReserve) Color(0xFFC2410C) else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun RowScope.BodyCell(text: String, weight: Float, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.bodySmall,
        color = color
    )
}
