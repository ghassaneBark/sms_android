package com.ma.sms.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Ecran de diagnostic temporaire (debug uniquement) : affiche la stack trace complete d'un crash
 * non rattrape, pour pouvoir la copier/screenshoter sans avoir besoin d'adb/logcat. A retirer une
 * fois le bug hors-ligne diagnostique. Tourne dans un process separe (voir manifest) pour survivre
 * au kill du process principal qui vient de crasher.
 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val error = intent.getStringExtra("error") ?: "Erreur inconnue"
        val titleText = intent.getStringExtra("title") ?: "L'application a rencontré une erreur (mode diagnostic)"

        val title = TextView(this).apply {
            text = titleText
            textSize = 18f
            setPadding(32, 48, 32, 16)
            gravity = Gravity.CENTER
        }

        val textView = TextView(this).apply {
            text = error
            setPadding(32, 0, 32, 32)
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(this).apply { addView(textView) }

        val copyButton = Button(this).apply {
            text = "Copier l'erreur"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("crash", error))
                Toast.makeText(this@CrashActivity, "Copié dans le presse-papiers", Toast.LENGTH_SHORT).show()
            }
        }
        val closeButton = Button(this).apply {
            text = "Fermer"
            setOnClickListener { finishAffinity() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(scrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(copyButton)
            addView(closeButton)
        }
        setContentView(root)
    }
}
