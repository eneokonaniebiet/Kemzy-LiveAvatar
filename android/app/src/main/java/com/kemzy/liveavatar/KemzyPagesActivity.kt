package com.kemzy.liveavatar

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class KemzyPagesActivity : AppCompatActivity() {
    private val pages = linkedMapOf(
        "Studio" to "Your live avatar workspace. Choose a source, connect the GPU renderer, and control your live output.",
        "Gallery" to "Your generated avatar workspace. This area is prepared for saved sessions, exported clips, favorites, and recent creations.",
        "Presets" to "Avatar presets and reusable looks. Organize source images, scene layouts, motion profiles, and output preferences.",
        "Performance" to "Live rendering diagnostics: connection state, renderer latency, frame rate, GPU status, and streaming quality.",
        "Settings" to "Camera, output quality, connection endpoint, privacy controls, notifications, and application preferences.",
        "Help Center" to "Guides for selecting a good source portrait, starting a live session, troubleshooting connection problems, and understanding renderer status.",
        "Privacy" to "Kémzy keeps privacy controls explicit. Review how source media, camera frames, and renderer sessions are handled before enabling live processing.",
        "Terms" to "Terms and usage information for Kémzy àvátâr. This page is a placeholder for the final published legal document.",
        "Pricing" to "Kémzy plan information and future service options. Pricing can be added when the service is ready for release.",
        "About Kémzy" to "Kémzy àvátâr is a real-time portrait animation studio built around a GPU neural-rendering pipeline."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showPage("Kémzy àvátâr", "Professional live avatar studio", pages.keys.first())
    }

    private fun showPage(title: String, subtitle: String, selected: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
            setBackgroundColor(0xFF0B0D12.toInt())
        }

        val brand = TextView(this).apply {
            text = "KÉMZY ÀVÁTÂR"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFB8C4FF.toInt())
        }
        root.addView(brand)

        val heading = TextView(this).apply {
            text = title
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 16, 0, 4)
        }
        root.addView(heading)

        val sub = TextView(this).apply {
            text = subtitle
            textSize = 15f
            setTextColor(0xFFA8AFBD.toInt())
            setPadding(0, 0, 0, 20)
        }
        root.addView(sub)

        val content = TextView(this).apply {
            text = pages[selected] ?: pages["Studio"]
            textSize = 17f
            setTextColor(0xFFE8EAF0.toInt())
            setLineSpacing(7f, 1f)
            setPadding(0, 18, 0, 28)
        }

        val scroll = ScrollView(this)
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        pages.keys.take(5).forEach { name ->
            val button = Button(this).apply {
                text = name
                isAllCaps = false
                setOnClickListener { showPage(name, "Kémzy workspace", name) }
            }
            nav.addView(button, LinearLayout.LayoutParams(0, 56, 1f))
        }
        root.addView(nav)

        val more = Button(this).apply {
            text = "Explore all Kémzy pages"
            isAllCaps = false
            setOnClickListener { showAllPages() }
        }
        root.addView(more)

        val back = Button(this).apply {
            text = "Back to Studio"
            isAllCaps = false
            setOnClickListener { finish() }
        }
        root.addView(back)

        setContentView(root)
    }

    private fun showAllPages() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 30, 24, 24)
            setBackgroundColor(0xFF0B0D12.toInt())
        }
        val heading = TextView(this).apply {
            text = "Kémzy"
            textSize = 30f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(heading)
        pages.keys.forEach { name ->
            val button = Button(this).apply {
                text = name
                isAllCaps = false
                setOnClickListener { showPage(name, "Kémzy workspace", name) }
            }
            root.addView(button)
        }
        val back = Button(this).apply {
            text = "Back to Studio"
            isAllCaps = false
            setOnClickListener { finish() }
        }
        root.addView(back)
        setContentView(root)
    }
}
