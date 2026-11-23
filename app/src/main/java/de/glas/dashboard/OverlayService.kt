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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

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
            .setContentText("Mit zwei Fingern oben am Rand nach unten wischen")
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
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(Prefs.stripHeight(this)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = statusBarHeight()   // direkt unter der Statusleiste
        }

        val v = FrameLayout(this)
        if (Prefs.showHandle(this)) {
            val handle = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dpf(3f)
                    setColor(0x8CFFFFFF.toInt())
                }
            }
            v.addView(handle, FrameLayout.LayoutParams(dp(56), dp(5), Gravity.CENTER))
        }

        var startY = 0f
        var dy = 0f
        var tracking = false
        var dragging = false
        val screenH = resources.displayMetrics.heightPixels.toFloat()

        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { tracking = false; dragging = false }
                MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                    startY = (e.getY(0) + e.getY(1)) / 2f
                    dy = 0f
                    tracking = true
                }
                MotionEvent.ACTION_MOVE -> if (tracking && e.pointerCount >= 2) {
                    dy = (e.getY(0) + e.getY(1)) / 2f - startY
                    if (dy > dpf(8f) || dragging) {
                        dragging = true
                        panel().drag(dy / (screenH * 0.45f))
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    if (tracking) {
                        tracking = false
                        if (dragging) panel().settle(dy > dpf(70f))
                        dragging = false
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
