package de.glas.dashboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Läuft dauerhaft im Hintergrund (Vordergrunddienst mit Benachrichtigung).
 * Legt oben am Bildschirm eine schmale, unsichtbare Wischzone über alle Apps.
 * Zwei Finger darin nach unten ziehen -> das Glas-Dashboard fährt mit den Fingern herein.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "de.glas.dashboard.START"
        const val ACTION_STOP = "de.glas.dashboard.STOP"
        const val ACTION_OPEN = "de.glas.dashboard.OPEN"
        const val ACTION_REFRESH = "de.glas.dashboard.REFRESH"
        const val CHANNEL_SERVICE = "overlay"
        const val CHANNEL_TIMER = "timer"

        @Volatile var running = false
            private set

        fun start(ctx: Context, action: String = ACTION_START) {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java).setAction(action))
        }

        fun stop(ctx: Context) {
            Prefs.setEnabled(ctx, false)
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }

    private lateinit var wm: WindowManager
    private var strip: View? = null
    private var panel: DashboardPanel? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        Holo.setAccent(Prefs.accent(this))
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_STOP || !Settings.canDrawOverlays(this)) {
            if (intent?.action == ACTION_STOP) Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        Prefs.setEnabled(this, true)
        when (intent?.action) {
            ACTION_REFRESH -> { removeStrip(); addStrip() }
            ACTION_OPEN -> { if (strip == null) addStrip(); panel().open() }
            else -> if (strip == null) addStrip()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        removeStrip()
        panel?.destroy()
        panel = null
        super.onDestroy()
    }

    /** Panel neu aufbauen (z. B. nach Farbwechsel) und optional einen Bereich öffnen. */
    fun rebuildPanel(openKey: String?) {
        Holo.setAccent(Prefs.accent(this))
        panel?.destroy()
        panel = null
        removeStrip(); addStrip()
        if (openKey != null) panel().openAt(openKey)
    }

    private fun panel(): DashboardPanel = panel ?: DashboardPanel(this, wm).also { panel = it }

    // ---------- Benachrichtigung / Vordergrunddienst ----------

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Hintergrunddienst", NotificationManager.IMPORTANCE_MIN)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TIMER, "Timer", NotificationManager.IMPORTANCE_HIGH)
        )
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, OpenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Dashboard bereit")
            .setContentText("Punkt antippen = Dashboard · gedrückt halten = neue Notiz")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Beenden", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- Wischzone ----------

    private fun addStrip() {
        if (Prefs.trigger(this) == 0) { addDot(); return }
        val pos = Prefs.position(this)
        val mode = Prefs.openMode(this)
        val thick = dp(Prefs.stripHeight(this))
        val top = pos == 0
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val sideLen = dp(160)
        val lp = WindowManager.LayoutParams(
            if (top) WindowManager.LayoutParams.MATCH_PARENT else thick,
            if (top) thick else sideLen,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val savedY = Prefs.handleY(this@OverlayService)
            if (top) {
                x = 0
                y = if (savedY >= 0) savedY.coerceIn(statusBarHeight(), screenH / 2) else statusBarHeight()
            } else {
                x = if (pos == 1) screenW - thick else 0
                y = if (savedY >= 0) savedY.coerceIn(statusBarHeight(), screenH - sideLen) else (screenH - sideLen) / 2
            }
        }

        val v = FrameLayout(this)
        if (Prefs.showHandle(this)) {
            val handle = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dpf(3f)
                    setColor(Holo.a(0xB3))
                }
            }
            v.addView(handle, if (top) FrameLayout.LayoutParams(dp(56), dp(5), Gravity.CENTER)
                              else FrameLayout.LayoutParams(dp(5), dp(56), Gravity.CENTER))
        }

        val allowSwipe = mode != 1
        val allowHold = mode != 0
        val slop = dpf(14f)
        var startY = 0f
        var dy = 0f
        var downX = 0f
        var downY = 0f
        var startLpX = 0
        var startLpY = 0
        var tracking = false
        var dragging = false
        var moving = false
        var held = false
        val holdRunnable = Runnable {
            held = true
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            panel().open()
        }

        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tracking = false; dragging = false; held = false; moving = false
                    downX = e.rawX; downY = e.rawY
                    startLpX = lp.x; startLpY = lp.y
                    if (allowHold) v.postDelayed(holdRunnable, 450)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    v.removeCallbacks(holdRunnable)
                    if (allowSwipe && e.pointerCount == 2 && !held && !moving) {
                        startY = (e.getY(0) + e.getY(1)) / 2f
                        dy = 0f
                        tracking = true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - downX; val my = e.rawY - downY
                    if (abs(mx) > slop || abs(my) > slop) v.removeCallbacks(holdRunnable)
                    if (tracking && e.pointerCount >= 2) {
                        dy = (e.getY(0) + e.getY(1)) / 2f - startY
                        if (dy > dpf(8f) || dragging) {
                            dragging = true
                            panel().drag(dy / (screenH * 0.45f))
                        }
                    } else if (!tracking && !held && e.pointerCount == 1 && (moving || abs(mx) > slop || abs(my) > slop)) {
                        // Griff mit einem Finger verschieben
                        moving = true
                        if (top) {
                            lp.y = (startLpY + my.toInt()).coerceIn(statusBarHeight(), screenH / 2)
                        } else {
                            lp.y = (startLpY + my.toInt()).coerceIn(statusBarHeight(), screenH - sideLen)
                            lp.x = (startLpX + mx.toInt()).coerceIn(0, screenW - thick)
                        }
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(holdRunnable)
                    if (tracking) {
                        tracking = false
                        if (dragging) panel().settle(dy > dpf(70f))
                        dragging = false
                    }
                    if (moving && e.actionMasked != MotionEvent.ACTION_POINTER_UP) {
                        moving = false
                        Prefs.setHandleY(this, lp.y)
                        if (!top) {
                            // an den nächsten Rand einrasten
                            val right = lp.x + thick / 2 > screenW / 2
                            lp.x = if (right) screenW - thick else 0
                            Prefs.setPosition(this, if (right) 1 else 2)
                            runCatching { wm.updateViewLayout(v, lp) }
                        }
                    }
                }
            }
            true
        }
        wm.addView(v, lp)
        strip = v
    }

    /** Schwebender Punkt: Tippen = Dashboard, gedrückt halten = neue Notiz, ziehen = verschieben. */
    private fun addDot() {
        val size = dp(56)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.int(this@OverlayService, "dot_x", screenW - size)
            y = Prefs.int(this@OverlayService, "dot_y", screenH / 3)
        }
        val v = FrameLayout(this)
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Holo.a(0xD9))
                setStroke(dp(2), 0xCCFFFFFF.toInt())
            }
            elevation = dpf(6f)
        }
        v.addView(dot, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        val ring = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0); setStroke(dp(1), Holo.a(0x66)) }
        }
        v.addView(ring, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))

        val slop = dpf(10f)
        var downX = 0f; var downY = 0f; var sx = 0; var sy = 0
        var moving = false; var held = false
        val hold = Runnable {
            held = true
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            panel().openQuickNote()
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; sx = lp.x; sy = lp.y; moving = false; held = false
                    dot.animate().scaleX(0.85f).scaleY(0.85f).setDuration(90).start()
                    v.postDelayed(hold, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - downX; val my = e.rawY - downY
                    if (!held && (moving || abs(mx) > slop || abs(my) > slop)) {
                        moving = true
                        v.removeCallbacks(hold)
                        lp.x = (sx + mx.toInt()).coerceIn(0, screenW - size)
                        lp.y = (sy + my.toInt()).coerceIn(0, screenH - size)
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(hold)
                    dot.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (moving) {
                        lp.x = if (lp.x + size / 2 > screenW / 2) screenW - size else 0   // an den Rand
                        runCatching { wm.updateViewLayout(v, lp) }
                        Prefs.setInt(this, "dot_x", lp.x); Prefs.setInt(this, "dot_y", lp.y)
                    } else if (!held && e.actionMasked == MotionEvent.ACTION_UP) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        panel().open()
                    }
                }
            }
            true
        }
        wm.addView(v, lp)
        strip = v
    }

    private fun removeStrip() {
        strip?.let { runCatching { wm.removeView(it) } }
        strip = null
    }
}
