package com.acapan.island

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        status = TextView(this).apply { textSize = 16f }
        col.addView(status)

        col.addView(button("Refresh status") { refreshStatus() })

        col.addView(button("1. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(button("2. Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        col.addView(Switch(this).apply {
            text = "Always-on island"
            isChecked = Prefs.alwaysOn(this@MainActivity)
            setPadding(0, dp(20), 0, dp(4))
            setOnCheckedChangeListener { _, on ->
                Prefs.setAlwaysOn(this@MainActivity, on)
                if (on) IslandController.start(this@MainActivity) else IslandController.stop()
                status.postDelayed({ refreshStatus() }, 600)
            }
        })

        col.addView(Switch(this).apply {
            text = "Debug: show idle pill in red"
            isChecked = Prefs.debugColor(this@MainActivity)
            setPadding(0, dp(8), 0, dp(4))
            setOnCheckedChangeListener { _, on ->
                Prefs.setDebugColor(this@MainActivity, on)
                if (Prefs.alwaysOn(this@MainActivity)) IslandController.start(this@MainActivity)
                status.postDelayed({ refreshStatus() }, 600)
            }
        })

        col.addView(label("Vertical position (dp from top)"))
        col.addView(slider(0, 40, Prefs.yOffset(this)) { Prefs.setYOffset(this, it) })

        col.addView(label("Idle width (dp)"))
        col.addView(slider(30, 120, Prefs.idleWidth(this)) { Prefs.setIdleWidth(this, it) })

        col.addView(label("Notification width (dp)"))
        col.addView(slider(100, 260, Prefs.width(this)) { Prefs.setWidth(this, it) })

        col.addView(button("Test island") { test() })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val overlay = Settings.canDrawOverlays(this)
        if (overlay && Prefs.alwaysOn(this)) IslandController.start(this)
        status.postDelayed({ refreshStatus() }, 600)
        refreshStatus()
    }

    private fun refreshStatus() {
        val overlay = Settings.canDrawOverlays(this)
        val listener = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        status.text = "Overlay permission: ${if (overlay) "OK" else "missing"}\n" +
            "Notification access: ${if (listener) "OK" else "missing"}\n" +
            "Always-on: ${if (Prefs.alwaysOn(this)) "on" else "off"}\n" +
            IslandController.describe()
    }

    private fun test() {
        if (Prefs.alwaysOn(this)) IslandController.start(this)
        IslandController.show(
            this,
            IslandData(
                appName = "Island",
                title = "Test notification",
                text = "If you can see this, the overlay works. Tap to expand, swipe up to dismiss.",
                icon = getDrawable(android.R.drawable.sym_def_app_icon)
            )
        )
        // check the state again after the pill has had time to settle back
        status.postDelayed({ refreshStatus() }, 4500)
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setPadding(0, dp(20), 0, 0)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun slider(min: Int, max: Int, value: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max - min
        progress = value - min
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p + min)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = test() // live preview
        })
    }
}
