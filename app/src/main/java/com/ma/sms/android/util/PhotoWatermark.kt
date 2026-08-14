package com.ma.sms.android.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream

/**
 * Incruste, en haut a gauche de la photo, les lignes d'information (date/heure, agent,
 * position, adresse) directement dans les pixels du JPEG, en rouge.
 */
object PhotoWatermark {

    fun apply(file: File, lines: List<String>) {
        if (lines.isEmpty()) return
        val source = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        if (bitmap !== source) source.recycle()

        val canvas = Canvas(bitmap)
        // Taille de texte proportionnelle a la largeur de l'image pour rester lisible
        // quelle que soit la resolution de capture.
        val textSize = (bitmap.width * 0.028f).coerceIn(24f, 56f)
        val padding = textSize * 0.6f
        val lineSpacing = textSize * 1.3f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(textSize * 0.15f, 2f, 2f, Color.BLACK)
        }

        var y = padding + textSize
        for (line in lines) {
            canvas.drawText(line, padding, y, textPaint)
            y += lineSpacing
        }

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bitmap.recycle()
    }
}
