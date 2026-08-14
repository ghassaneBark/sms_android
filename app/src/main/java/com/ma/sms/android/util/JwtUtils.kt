package com.ma.sms.android.util

import android.util.Base64
import org.json.JSONObject

/**
 * Lecture minimale des claims d'un JWT (sans verification de signature : le token
 * vient de notre propre TokenManager, deja valide par le backend a chaque appel API).
 */
object JwtUtils {

    fun extractDisplayName(token: String?): String? {
        if (token.isNullOrBlank()) return null
        val json = decodePayload(token) ?: return null
        json.optString("name").ifBlank { null }?.let { return it }
        val fullName = listOfNotNull(
            json.optString("given_name").ifBlank { null },
            json.optString("family_name").ifBlank { null }
        ).joinToString(" ").ifBlank { null }
        if (fullName != null) return fullName
        return json.optString("preferred_username").ifBlank { null }
    }

    private fun decodePayload(token: String): JSONObject? {
        val parts = token.split(".")
        if (parts.size < 2) return null
        return runCatching {
            var payload = parts[1]
            val paddingNeeded = (4 - payload.length % 4) % 4
            if (paddingNeeded > 0) payload += "=".repeat(paddingNeeded)
            val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
            JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }
}
