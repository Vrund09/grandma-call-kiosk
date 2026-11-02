package com.family.grandmacall

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Family-only screen, reached by holding the top-left corner of the home screen for 5 seconds. */
class AdminActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StatusBarBlocker.hide(this)
        Kiosk.startFamilyMode()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            setBackgroundColor(Color.WHITE)
        }
        column.addView(TextView(this).apply { text = "Grandma Call — family menu"; textSize = 22f; setTextColor(Color.BLACK) })
        column.addView(TextView(this).apply {
            text = "For the next 10 minutes the phone won't jump back to the photos, so you can use Settings."
            textSize = 15f; setTextColor(Color.DKGRAY)
        })
        fun button(label: String, action: () -> Unit) = column.addView(Button(this).apply {
            text = label; textSize = 20f
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = 24 })

        button("Android settings") { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        button("Choose home app") { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
        button("Normal phone (restore hidden apps, release home screen)") {
            Kiosk.release(this)
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        button("Open Samsung home screen") { Kiosk.openNormalPhone(this) }
        button("Back to photos") {
            Kiosk.endFamilyMode()
            finish()
        }
        setContentView(column)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(autoClose, 120_000)
    }

    override fun onPause() {
        handler.removeCallbacks(autoClose)
        super.onPause()
    }
}
