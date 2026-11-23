package de.glas.dashboard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.content.res.ColorStateList

/** Einrichtung: Berechtigungen, Dienst an/aus, Wischzone einstellen. */
class MainActivity : Activity() {

    private val rose = Holo.ICE
    private lateinit var status: TextView
    private lateinit var btnOverlay: TextView
    private lateinit var btnNotif: TextView
    private lateinit var btnBattery: TextView
    private lateinit var btnListener: TextView
    private lateinit var btnService: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Holo.NAVY)
            addView(col)
            @Suppress("DEPRECATION")
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }

        col.addView(text("Glas-Dashboard", 34f, Holo.light).apply { setShadowLayer(dpf(14f), 0f, 0f, Holo.ICE) })
        col.addView(text("Ein transparentes Panel, das über allen Apps liegt. Zwei Finger oben am Rand nach unten ziehen – schon ist es da.", 16f).apply {
            alpha = 0.8f; setPadding(0, dp(6), 0, dp(18))
        })

        status = text("", 15f).apply { setPadding(dp(14), dp(12), dp(14), dp(12)) }
        col.addView(status, lp(bottom = 18))

        btnOverlay = button("1 · Über anderen Apps einblenden erlauben") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btnNotif = button("2 · Benachrichtigungen erlauben") {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        btnBattery = button("3 · Akku-Optimierung ausschalten (läuft zuverlässiger)") {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        btnListener = button("4 · Benachrichtigungen & Musik (Spotify) lesen") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        btnService = button("Dienst starten") {
            if (OverlayService.running) OverlayService.stop(this) else if (Settings.canDrawOverlays(this)) OverlayService.start(this)
            col.postDelayed({ refresh() }, 300)
        }
        val btnTest = button("Dashboard jetzt öffnen") {
            if (Settings.canDrawOverlays(this)) OverlayService.start(this, OverlayService.ACTION_OPEN)
        }
        listOf(btnOverlay, btnNotif, btnBattery, btnListener, btnService, btnTest).forEach { col.addView(it, lp(bottom = 10)) }

        // Einstellungen der Wischzone
        col.addView(text("Höhe der Wischzone oben", 15f).apply { setPadding(0, dp(18), 0, 0) })
        val hVal = text("${Prefs.stripHeight(this)} dp", 13f).apply { alpha = 0.7f }
        col.addView(hVal)
        col.addView(SeekBar(this).apply {
            max = 56
            progress = Prefs.stripHeight(this@MainActivity) - 16
            progressTintList = ColorStateList.valueOf(rose)
            thumbTintList = ColorStateList.valueOf(rose)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { hVal.text = "${p + 16} dp" }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    Prefs.setStripHeight(this@MainActivity, s.progress + 16)
                    if (OverlayService.running) OverlayService.start(this@MainActivity, OverlayService.ACTION_REFRESH)
                }
            })
        }, lp(bottom = 8))
        col.addView(CheckBox(this).apply {
            text = "Kleinen Griff anzeigen, damit man die Zone sieht"
            setTextColor(Color.WHITE)
            buttonTintList = ColorStateList.valueOf(rose)
            isChecked = Prefs.showHandle(this@MainActivity)
            setOnCheckedChangeListener { _, c ->
                Prefs.setShowHandle(this@MainActivity, c)
                if (OverlayService.running) OverlayService.start(this@MainActivity, OverlayService.ACTION_REFRESH)
            }
        }, lp(bottom = 18))

        col.addView(text(
            "So öffnest du das Dashboard:\n" +
            "• Zwei Finger direkt unter der Statusleiste ansetzen und nach unten ziehen.\n" +
            "• Oder: Schnelleinstellungen bearbeiten (Stift-Symbol) und die Kachel „Dashboard“ hinzufügen.\n" +
            "• Oder: auf die Benachrichtigung „Dashboard bereit“ tippen.\n\n" +
            "Schließen: zwei Finger nach oben, daneben tippen oder Zurück.\n\n" +
            "Hinweis: Einzelne Taps genau in der schmalen Wischzone landen nicht in der App darunter. " +
            "Stell die Zone so niedrig ein, wie es für dich bequem ist.", 14f
        ).apply { alpha = 0.75f })

        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g); refresh()
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        mark(btnOverlay, overlay); mark(btnNotif, notif); mark(btnBattery, battery)
        mark(btnListener, NotifListener.isEnabled(this))

        val running = OverlayService.running || (overlay && Prefs.enabled(this))
        btnService.text = if (running) "Dienst beenden" else "Dienst starten"
        status.text = when {
            !overlay -> "Noch nicht bereit: Erlaube zuerst „Über anderen Apps einblenden“."
            running -> "Läuft im Hintergrund. Wisch mit zwei Fingern oben nach unten!"
            else -> "Bereit. Tippe auf „Dienst starten“."
        }
        status.background = GradientDrawable().apply {
            cornerRadius = dpf(14f)
            setColor(if (running) 0x337FDBFF else 0x1FFFFFFF)
        }
    }

    private fun mark(b: TextView, ok: Boolean) {
        b.alpha = if (ok) 0.55f else 1f
        b.isEnabled = !ok
        if (ok && !b.text.endsWith("✓")) b.text = "${b.text}  ✓"
    }

    private fun text(t: String, sp: Float, tf: Typeface? = null) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (tf != null) typeface = tf
    }

    private fun button(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(52)
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = CutDrawable(dpf(14f), Holo.PANEL, Holo.LINE, dpf(1f))
        setOnClickListener { onClick() }
    }

    private fun lp(bottom: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(bottom) }
}
