package com.ma.sms.android.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Banniere persistante affichee en haut de l'ecran tant que l'appareil est hors connexion.
 * Purement informative (pas de bouton de fermeture) : l'agent terrain doit savoir en permanence
 * que ses actions (photos, mises a jour) seront synchronisees plus tard plutot que perdues.
 */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.width(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Hors ligne — les modifications seront synchronisées automatiquement",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
