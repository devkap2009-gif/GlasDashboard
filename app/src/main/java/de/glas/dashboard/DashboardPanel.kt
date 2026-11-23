package de.glas.dashboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.abs

private const val SCRIM_MAX_ALPHA = 200   // 0..255 – wie dunkel der Hintergrund maximal wird
private const val BLUR_MAX = 60           // Unschärfe in px (Android 12+)
private const val MAX_NOTIFS = 6

/**
 * Das Overlay-Panel im „Arktis-Kommando“-Design. Ein eigenes Vollbild-Fenster
 * über allen Apps, das nur existiert, solange es sichtbar ist.
 */
@SuppressLint("SetTextI18n")
class DashboardPanel(private val ctx: Context, private val wm: WindowManager) {

    private val main = Handler(Looper.getMainLooper())
    private val screenH = ctx.resources.displayMetrics.heightPixels.toFloat()
    private val blurOk = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
    private val scrim = ColorDrawable(Holo.SCRIM).apply { alpha = 0 }

    private val root = PanelRoot(ctx)
    private val scroll = ScrollView(ctx)
    private val sheet = LinearLayout(ctx)

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        if (blurOk) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
    }

    private var attached = false
    private var progress = 0f
    private var anim: ValueAnimator? = null

    // Widgets, die aktualisiert werden
    private lateinit var batteryText: TextView
    private lateinit var batteryBar: ProgressBar
    private lateinit var timerText: TextView
    private lateinit var timerBtn: TextView
    private lateinit var todoBox: LinearLayout
    private lateinit var notifTile: LinearLayout
    private lateinit var notifBox: LinearLayout
    private lateinit var notifClear: TextView
    private lateinit var mediaTile: LinearLayout
    private lateinit var mediaArt: ImageView
    private lateinit var mediaTitle: TextView
    private lateinit var mediaArtist: TextView
    private lateinit var mediaApp: TextView
    private lateinit var mediaBar: ProgressBar
    private lateinit var mediaPlay: ImageView
    private val toggleViews = mutableListOf<Triple<FrameLayout, ImageView, () -> Boolean>>()

    // ---------- Timer ----------
    private val presets = intArrayOf(1, 5, 10, 25)
    private var presetIdx = 1
    private var timerLeft = presets[presetIdx] * 60
    private var timerRunning = false
    private val tick = object : Runnable {
        override fun run() {
            timerLeft--
            if (timerLeft <= 0) { timerDone(); return }
            updateTimer()
            main.postDelayed(this, 1000)
        }
    }

    // ---------- Taschenlampe ----------
    private val cam = ctx.getSystemService(CameraManager::class.java)
    private val torchId: String? = runCatching {
        cam.cameraIdList.firstOrNull {
            cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()
    private var torchOn = false
    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) { torchOn = enabled; refreshToggles() }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = showBattery(i)
    }

    // ---------- Benachrichtigungen & Musik ----------
    private val listenerComp = ComponentName(ctx, NotifListener::class.java)
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private var controller: MediaController? = null
    private val notifWatcher: () -> Unit = { main.post { renderNotifs(); pickController() } }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { main.post { pickController() } }
    private val mediaCb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = renderMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = renderMedia()
        override fun onSessionDestroyed() = pickController()
    }
    private val mediaTick = object : Runnable {
        override fun run() { updateMediaBar(); main.postDelayed(this, 1000) }
    }

    init {
        torchId?.let { runCatching { cam.registerTorchCallback(torchCb, main) } }
        build()
        root.background = scrim
        root.isClickable = true
        root.setOnClickListener { close() }       // daneben tippen = schließen
        root.onBack = { close() }
        root.onDrag = { dy -> anim?.cancel(); setProgress(1f + dy / (screenH * 0.45f)) }
        root.onRelease = { dy -> settle(dy > -ctx.dpf(70f)) }
    }

    // ---------- öffentliche Steuerung ----------

    fun drag(p: Float) {
        anim?.cancel()
        attach()
        setProgress(p)
    }

    fun open() = settle(true)
    fun close() = settle(false)

    fun settle(open: Boolean) {
        if (!attached) {
            if (!open) return
            attach(); setProgress(0f)
        }
        anim?.cancel()
        val target = if (open) 1f else 0f
        if (!open) hideKeyboard()
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = (320 * abs(target - progress)).toLong().coerceAtLeast(120)
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) {
                    if (!cancelled && target == 0f) detach()
                }
            })
            start()
        }
    }

    fun destroy() {
        anim?.cancel()
        main.removeCallbacks(tick)
        detach()
        runCatching { cam.unregisterTorchCallback(torchCb) }
    }

    // ---------- Fenster ----------

    private fun attach() {
        if (attached) return
        if (Build.VERSION.SDK_INT >= 31) lp.blurBehindRadius = 0
        runCatching { wm.addView(root, lp) }.onSuccess {
            attached = true
            ctx.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.let { showBattery(it) }
            refreshToggles()
            updateTimer()
            NotifListener.watchers += notifWatcher
            if (NotifListener.isEnabled(ctx)) {
                runCatching { msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComp, main) }
            }
            renderNotifs()
            pickController()
            main.post(mediaTick)
        }
    }

    private fun detach() {
        if (!attached) return
        NotifListener.watchers -= notifWatcher
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.let { runCatching { it.unregisterCallback(mediaCb) } }
        controller = null
        main.removeCallbacks(mediaTick)
        runCatching { ctx.unregisterReceiver(batteryReceiver) }
        runCatching { wm.removeView(root) }
        attached = false
        progress = 0f
    }

    private fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        scroll.translationY = -(1f - progress) * screenH
        scroll.alpha = 0.4f + 0.6f * progress
        scrim.alpha = (progress * SCRIM_MAX_ALPHA).toInt()
        if (blurOk && attached && Build.VERSION.SDK_INT >= 31) {
            val r = (progress * BLUR_MAX).toInt()
            if (abs(r - lp.blurBehindRadius) >= 6 || r == 0 || r == BLUR_MAX) {
                lp.blurBehindRadius = r
                runCatching { wm.updateViewLayout(root, lp) }
            }
        }
    }

    private fun hideKeyboard() {
        ctx.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(root.windowToken, 0)
        root.findFocus()?.clearFocus()
    }

    // ---------- Bausteine ----------

    private fun cut(fill: Int = Holo.PANEL, stroke: Int = Holo.LINE, c: Float = 18f, w: Float = 1f) =
        CutDrawable(ctx.dpf(c), fill, stroke, ctx.dpf(w))

    private fun tile() = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = cut()
        setPadding(ctx.dp(16), ctx.dp(14), ctx.dp(16), ctx.dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = ctx.dp(10) }
    }

    private fun row(a: View, b: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = ctx.dp(5) })
        addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = ctx.dp(5) })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = ctx.dp(10) }
    }

    /** Kleine Überschrift in Versalien mit Sperrung, eisblau. */
    private fun label(t: String) = TextView(ctx).apply {
        text = t.uppercase()
        setTextColor(Holo.ICE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Holo.label
        letterSpacing = 0.18f
    }

    private fun small(t: String) = TextView(ctx).apply {
        text = t
        setTextColor(Holo.TEXT_DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private fun big(t: String) = TextView(ctx).apply {
        text = t
        setTextColor(Holo.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        typeface = Holo.light
        maxLines = 1
        setShadowLayer(ctx.dpf(10f), 0f, 0f, Holo.ICE_DIM)
    }

    private fun pill(t: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = t.uppercase()
        setTextColor(Holo.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Holo.label
        letterSpacing = 0.12f
        gravity = Gravity.CENTER
        minHeight = ctx.dp(44); minWidth = ctx.dp(44)
        setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
        background = cut(Holo.PANEL_DEEP, Holo.LINE, 10f)
        setOnClickListener { onClick() }
    }

    private fun iconBtn(res: Int, desc: String, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(Holo.ICE)
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
        background = cut(Holo.PANEL_DEEP, Holo.LINE, 10f)
        setOnClickListener { onClick() }
    }

    private fun input(hint: String, multi: Boolean) = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(0x80B9D7EE.toInt())
        setTextColor(Holo.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = cut(Holo.PANEL_DEEP, Holo.LINE, 10f)
        setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        if (multi) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; gravity = Gravity.TOP or Gravity.START
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            maxLines = 1
        }
    }

    private fun bar() = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        progressTintList = ColorStateList.valueOf(Holo.ICE)
        progressBackgroundTintList = ColorStateList.valueOf(0x337FDBFF)
    }

    // ---------- Aufbau ----------

    private fun build() {
        sheet.orientation = LinearLayout.VERTICAL
        sheet.isClickable = true   // Tippen auf das Panel schließt es nicht
        val side = ctx.dp(16)
        sheet.setPadding(side, ctx.statusBarHeight() + ctx.dp(14), side, ctx.dp(20))

        // Kopfzeile
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(View(ctx).apply { setBackgroundColor(Holo.ICE) }, LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)).apply { marginEnd = ctx.dp(8) })
        head.addView(label("Kommandozentrale · online"))
        sheet.addView(head)

        val clock = TextClock(ctx).apply {
            format24Hour = "HH:mm"; format12Hour = "h:mm"
            setTextColor(Holo.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 80f)
            typeface = Holo.thin
            includeFontPadding = false
            setShadowLayer(ctx.dpf(18f), 0f, 0f, Holo.ICE)
            setPadding(0, ctx.dp(6), 0, 0)
        }
        val date = TextClock(ctx).apply {
            format24Hour = "EEEE · d. MMMM"; format12Hour = "EEEE · d. MMMM"
            setTextColor(Holo.TEXT_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Holo.label
            letterSpacing = 0.08f
            setPadding(ctx.dp(4), 0, 0, ctx.dp(14))
        }
        sheet.addView(clock); sheet.addView(date)
        // Linie unter der Uhr
        sheet.addView(View(ctx).apply { setBackgroundColor(Holo.LINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)).apply { bottomMargin = ctx.dp(14) })

        sheet.addView(mediaTile())
        sheet.addView(notifTileView())
        sheet.addView(tile().apply { addView(toggleRow()) })
        sheet.addView(row(batteryTile(), timerTile()))
        sheet.addView(todoTile())
        sheet.addView(noteTile())

        sheet.addView(View(ctx).apply { background = cut(Holo.ICE, Holo.ICE, 2f, 0f) },
            LinearLayout.LayoutParams(ctx.dp(46), ctx.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(14) })
        sheet.addView(small("Schließen: zwei Finger nach oben, daneben tippen oder Zurück").apply {
            gravity = Gravity.CENTER; setPadding(0, ctx.dp(8), 0, 0); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })

        scroll.isFillViewport = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(sheet, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ).apply {
            val maxW = ctx.dp(560)
            if (ctx.resources.displayMetrics.widthPixels > maxW) width = maxW
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        })
    }

    private fun openSettings(i: Intent) {
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        close()
    }

    /** Öffnet eine fremde PendingIntent (z. B. Tipp auf Benachrichtigung). */
    private fun firePending(pi: PendingIntent?) {
        if (pi == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("DEPRECATION")
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, null, null, null, null, opts.toBundle())
            } else {
                pi.send()
            }
        }
        close()
    }

    // ---------- Musik (Spotify & Co.) ----------

    private fun mediaTile() = tile().apply {
        mediaTile = this
        visibility = View.GONE
        mediaApp = label("Wiedergabe")
        addView(mediaApp)
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ctx.dp(10), 0, 0) }
        mediaArt = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = cut(Holo.PANEL_DEEP, Holo.LINE, 10f)
            clipToOutline = false
        }
        line.addView(mediaArt, LinearLayout.LayoutParams(ctx.dp(64), ctx.dp(64)).apply { marginEnd = ctx.dp(14) })
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        mediaTitle = TextView(ctx).apply {
            setTextColor(Holo.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        mediaArtist = small("").apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        texts.addView(mediaTitle); texts.addView(mediaArtist)
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(line)
        mediaBar = bar().apply { max = 1000 }
        addView(mediaBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(4)).apply { topMargin = ctx.dp(12) })
        val ctrls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val size = ctx.dp(52)
        ctrls.addView(iconBtn(R.drawable.ic_prev, "Zurück") { controller?.transportControls?.skipToPrevious() }, LinearLayout.LayoutParams(size, size))
        mediaPlay = iconBtn(R.drawable.ic_play, "Abspielen/Pause") {
            val c = controller ?: return@iconBtn
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        }
        ctrls.addView(mediaPlay, LinearLayout.LayoutParams(ctx.dp(64), size).apply { marginStart = ctx.dp(14); marginEnd = ctx.dp(14) })
        ctrls.addView(iconBtn(R.drawable.ic_next, "Weiter") { controller?.transportControls?.skipToNext() }, LinearLayout.LayoutParams(size, size))
        addView(ctrls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(12) })
        // Tipp aufs Cover/Titel öffnet die Player-App
        line.setOnClickListener { firePending(controller?.sessionActivity) }
    }

    private fun pickController() {
        if (!attached) return
        val list = if (NotifListener.isEnabled(ctx))
            runCatching { msm.getActiveSessions(listenerComp) }.getOrDefault(emptyList())
        else emptyList()
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.let { runCatching { it.unregisterCallback(mediaCb) } }
            controller = best
            best?.registerCallback(mediaCb, main)
        }
        renderMedia()
    }

    private fun renderMedia() {
        val c = controller
        val md = c?.metadata
        if (c == null || md == null) { mediaTile.visibility = View.GONE; return }
        mediaTile.visibility = View.VISIBLE
        val appName = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault("Wiedergabe")
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        mediaApp.text = ((if (playing) "▶ " else "❚❚ ") + appName).uppercase()
        mediaTitle.text = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unbekannter Titel"
        mediaArtist.text = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art != null) mediaArt.setImageBitmap(art) else mediaArt.setImageDrawable(null)
        mediaPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        updateMediaBar()
    }

    private fun updateMediaBar() {
        if (!::mediaBar.isInitialized) return
        val c = controller ?: return
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val st = c.playbackState ?: return
        if (dur <= 0) { mediaBar.progress = 0; return }
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        mediaBar.progress = ((pos.coerceIn(0, dur) * 1000) / dur).toInt()
    }

    // ---------- Benachrichtigungen ----------

    private fun notifTileView() = tile().apply {
        notifTile = this
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("Benachrichtigungen"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        notifClear = pill("Alle löschen") {
            runCatching { NotifListener.instance?.cancelAllNotifications() }
        }
        head.addView(notifClear)
        addView(head)
        notifBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addView(notifBox)
    }

    private fun renderNotifs() {
        if (!::notifBox.isInitialized) return
        notifBox.removeAllViews()
        if (!NotifListener.isEnabled(ctx)) {
            notifClear.visibility = View.GONE
            notifBox.addView(small("Erlaube den Benachrichtigungszugriff, dann siehst du hier deine Nachrichten und kannst Spotify steuern.").apply {
                setPadding(0, ctx.dp(8), 0, ctx.dp(10))
            })
            notifBox.addView(pill("Zugriff erlauben") {
                openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return
        }
        val all = runCatching { NotifListener.instance?.activeNotifications?.toList() }.getOrNull() ?: emptyList()
        val list = all.filter { sbn ->
            val n = sbn.notification
            sbn.packageName != ctx.packageName &&
                (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                !sbn.isOngoing &&
                !n.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank()
        }.sortedByDescending { it.postTime }

        notifClear.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (list.isEmpty()) {
            notifBox.addView(small("Keine neuen Benachrichtigungen.").apply { setPadding(0, ctx.dp(10), 0, ctx.dp(2)) })
            return
        }
        list.take(MAX_NOTIFS).forEach { notifBox.addView(notifRow(it)) }
        if (list.size > MAX_NOTIFS) {
            notifBox.addView(small("+ ${list.size - MAX_NOTIFS} weitere").apply { setPadding(0, ctx.dp(8), 0, 0) })
        }
    }

    private fun notifRow(sbn: StatusBarNotification): View {
        val n = sbn.notification
        val pm = ctx.packageManager
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }
            .getOrDefault(sbn.packageName.substringAfterLast('.'))
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val time = DateUtils.getRelativeTimeSpanString(sbn.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(10), 0, ctx.dp(10))
            setOnClickListener { firePending(n.contentIntent) }
        }
        val icon = ImageView(ctx).apply {
            val d = runCatching { n.smallIcon?.loadDrawable(ctx) }.getOrNull()
            if (d != null) { setImageDrawable(d); imageTintList = ColorStateList.valueOf(Holo.ICE) }
            else runCatching { setImageDrawable(pm.getApplicationIcon(sbn.packageName)) }
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            background = cut(Holo.PANEL_DEEP, Holo.LINE, 8f)
        }
        line.addView(icon, LinearLayout.LayoutParams(ctx.dp(40), ctx.dp(40)).apply { marginEnd = ctx.dp(12) })

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label("$app · $time").apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(TextView(ctx).apply {
            this.text = title
            setTextColor(Holo.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (text.isNotBlank()) col.addView(small(text).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        line.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (sbn.isClearable) {
            line.addView(TextView(ctx).apply {
                this.text = "×"
                contentDescription = "Entfernen"
                setTextColor(Holo.ICE_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
                setOnClickListener { runCatching { NotifListener.instance?.cancelNotification(sbn.key) } }
            }, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
        }

        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(View(ctx).apply { setBackgroundColor(0x267FDBFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)))
        wrap.addView(line)
        return wrap
    }

    // ---------- Schalter ----------

    private fun toggleRow(): View {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val rowView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        fun add(icon: Int, name: String, isOn: () -> Boolean, onTap: () -> Unit) {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val img = ImageView(ctx).apply { setImageResource(icon) }
            val box = FrameLayout(ctx).apply {
                contentDescription = name
                setOnClickListener { onTap(); refreshToggles() }
                addView(img, FrameLayout.LayoutParams(ctx.dp(26), ctx.dp(26), Gravity.CENTER))
            }
            col.addView(box, LinearLayout.LayoutParams(ctx.dp(58), ctx.dp(58)))
            col.addView(label(name).apply {
                gravity = Gravity.CENTER; setPadding(0, ctx.dp(6), 0, 0); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(Holo.TEXT_DIM); maxLines = 1
            })
            rowView.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            toggleViews += Triple(box, img, isOn)
        }

        add(R.drawable.ic_wifi, "WLAN", {
            runCatching { ctx.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false)
        }) {
            openSettings(if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                         else Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        add(R.drawable.ic_bt, "Bluetooth", {
            runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false)
        }) { openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        add(R.drawable.ic_dnd, "Ruhe", {
            nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        }) {
            if (nm.isNotificationPolicyAccessGranted) {
                val on = nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
                nm.setInterruptionFilter(
                    if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY
                )
            } else openSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        add(R.drawable.ic_flash, "Lampe", { torchOn }) {
            torchId?.let { id -> runCatching { cam.setTorchMode(id, !torchOn) } }
        }
        return rowView
    }

    private fun refreshToggles() {
        // kurz verzögert, damit das System den neuen Zustand schon kennt
        main.postDelayed({
            toggleViews.forEach { (box, img, isOn) ->
                val on = runCatching { isOn() }.getOrDefault(false)
                box.background = if (on) cut(Holo.ICE, Holo.ICE, 12f) else cut(Holo.PANEL_DEEP, Holo.LINE, 12f)
                img.imageTintList = ColorStateList.valueOf(if (on) Holo.NAVY else Holo.ICE)
            }
        }, 150)
    }

    // ---------- Akku ----------

    private fun batteryTile() = tile().apply {
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        addView(label("Energie"))
        batteryText = big("–")
        addView(batteryText)
        batteryBar = bar().apply { max = 100 }
        addView(batteryBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(6)).apply { topMargin = ctx.dp(10) })
    }

    private fun showBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return
        val pct = level * 100 / scale.coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        batteryText.text = "$pct %" + if (charging) " ⚡" else ""
        batteryBar.progress = pct
    }

    // ---------- Timer ----------

    private fun timerTile() = tile().apply {
        (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
        addView(label("Timer"))
        timerText = big("05:00").apply {
            setOnClickListener {
                if (!timerRunning) {
                    presetIdx = (presetIdx + 1) % presets.size
                    timerLeft = presets[presetIdx] * 60
                    updateTimer()
                }
            }
        }
        addView(timerText)
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        timerBtn = pill("Start") { toggleTimer() }
        btns.addView(timerBtn)
        btns.addView(pill("↺") { resetTimer() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ctx.dp(8) })
        addView(btns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(8) })
    }

    private fun toggleTimer() {
        if (timerRunning) {
            timerRunning = false; main.removeCallbacks(tick)
        } else {
            if (timerLeft <= 0) timerLeft = presets[presetIdx] * 60
            timerRunning = true; main.postDelayed(tick, 1000)
        }
        updateTimer()
    }

    private fun resetTimer() {
        timerRunning = false; main.removeCallbacks(tick)
        timerLeft = presets[presetIdx] * 60
        updateTimer()
    }

    private fun updateTimer() {
        if (!::timerText.isInitialized) return
        timerText.text = if (timerLeft <= 0) "Fertig!" else "%02d:%02d".format(timerLeft / 60, timerLeft % 60)
        timerBtn.text = if (timerRunning) "PAUSE" else "START"
        timerBtn.setTextColor(if (timerRunning) Holo.NAVY else Holo.WHITE)
        timerBtn.background = if (timerRunning) cut(Holo.ICE, Holo.ICE, 10f) else cut(Holo.PANEL_DEEP, Holo.LINE, 10f)
    }

    private fun timerDone() {
        timerRunning = false
        timerLeft = 0
        updateTimer()
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else ctx.getSystemService(Vibrator::class.java)
        vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
        runCatching {
            ctx.getSystemService(NotificationManager::class.java).notify(
                2,
                Notification.Builder(ctx, OverlayService.CHANNEL_TIMER)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Timer fertig")
                    .setContentText("${presets[presetIdx]} Minuten sind um.")
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    // ---------- Aufgaben ----------

    private fun todoTile() = tile().apply {
        addView(label("Aufgaben").apply { setPadding(0, 0, 0, ctx.dp(8)) })
        val field = input("Neue Aufgabe …", false)
        field.setOnEditorActionListener { v, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                val t = v.text.toString().trim()
                if (t.isNotEmpty()) {
                    val list = Prefs.todos(ctx); list.add(Todo(t, false)); Prefs.setTodos(ctx, list)
                    v.text = ""
                    renderTodos()
                }
                true
            } else false
        }
        addView(field)
        todoBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        addView(todoBox)
        renderTodos()
    }

    private fun renderTodos() {
        todoBox.removeAllViews()
        val list = Prefs.todos(ctx)
        list.forEachIndexed { i, todo ->
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ctx.dp(44)
            }
            val cb = CheckBox(ctx).apply {
                text = todo.text
                isChecked = todo.done
                setTextColor(if (todo.done) Holo.ICE_DIM else Holo.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                buttonTintList = ColorStateList.valueOf(Holo.ICE)
                paintFlags = if (todo.done) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                             else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                setOnCheckedChangeListener { _, checked ->
                    val l = Prefs.todos(ctx)
                    if (i < l.size) { l[i] = l[i].copy(done = checked); Prefs.setTodos(ctx, l) }
                    main.post { renderTodos() }
                }
            }
            val del = TextView(ctx).apply {
                text = "×"
                contentDescription = "Löschen"
                setTextColor(Holo.ICE_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.CENTER
                setOnClickListener {
                    val l = Prefs.todos(ctx)
                    if (i < l.size) { l.removeAt(i); Prefs.setTodos(ctx, l) }
                    main.post { renderTodos() }
                }
            }
            line.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(del, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
            todoBox.addView(line)
        }
    }

    // ---------- Notiz ----------

    private fun noteTile() = tile().apply {
        addView(label("Notiz").apply { setPadding(0, 0, 0, ctx.dp(8)) })
        val note = input("Schnell etwas aufschreiben …", true)
        note.setText(Prefs.note(ctx))
        note.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { Prefs.setNote(ctx, s?.toString() ?: "") }
        })
        addView(note)
    }
}

/**
 * Wurzel-View des Panels: erkennt zwei Finger nach oben (schließen), auch wenn
 * darunter gescrollt oder getippt wird, und fängt die Zurück-Taste ab.
 */
class PanelRoot(ctx: Context) : FrameLayout(ctx) {
    var onBack: () -> Unit = {}
    var onDrag: (Float) -> Unit = {}
    var onRelease: (Float) -> Unit = {}

    private val slop = ctx.dpf(24f)
    private var startY = 0f
    private var dy = 0f
    private var tracking = false
    private var stealing = false
    private var swallow = false

    private fun avg(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2f

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { tracking = false; stealing = false; swallow = false }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && !stealing) {
                startY = avg(e); dy = 0f; tracking = true
            }
            MotionEvent.ACTION_MOVE -> if (tracking && e.pointerCount >= 2) {
                dy = avg(e) - startY
                if (!stealing && dy < -slop) {
                    stealing = true
                    val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (stealing) {
                    stealing = false; tracking = false; swallow = true
                    onRelease(dy)
                } else if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) tracking = false
            }
        }
        if (stealing) { onDrag(dy); return true }
        if (swallow) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swallow = false
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) {
            if (e.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        return super.dispatchKeyEvent(e)
    }
}
