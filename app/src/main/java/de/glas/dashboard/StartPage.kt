package de.glas.dashboard

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Startbereich: Profil-Kurzinfo, Fokus-Timer, Musik, Benachrichtigungen, Schalter. */
class StartPage(
    private val ui: Ui,
    private val close: () -> Unit,
    private val openProfile: () -> Unit
) : Page {
    override val key = "start"
    override val title = "Start"

    private val ctx = ui.ctx
    private val main = Handler(Looper.getMainLooper())
    private val col = ui.column()
    override val view: View = ui.page(col)
    private var shown = false

    private lateinit var profName: TextView
    private lateinit var profRole: TextView
    private lateinit var profAvatar: TextView
    private lateinit var timerText: TextView
    private lateinit var timerBtn: TextView
    private lateinit var focusInfo: TextView
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

    // Fokus-Timer
    private val presets = intArrayOf(25, 50, 90, 5)
    private var presetIdx = 0
    private var timerLeft = presets[0] * 60
    private var timerRunning = false
    private val tick = object : Runnable {
        override fun run() {
            timerLeft--
            if (timerLeft <= 0) { timerDone(); return }
            updateTimer(); main.postDelayed(this, 1000)
        }
    }

    // Taschenlampe
    private val cam = ctx.getSystemService(CameraManager::class.java)
    private val torchId: String? = runCatching {
        cam.cameraIdList.firstOrNull { cam.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    }.getOrNull()
    private var torchOn = false
    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) { torchOn = enabled; refreshToggles() }
        }
    }

    // Benachrichtigungen & Musik
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
        col.addView(profileCard())
        col.addView(timerTile())
        col.addView(mediaTileView())
        col.addView(notifTileView())
        col.addView(ui.tile().apply { addView(toggleRow()) })
    }

    override fun onShow() {
        shown = true
        renderProfile()
        refreshToggles()
        updateTimer()
        NotifListener.watchers += notifWatcher
        if (NotifListener.isEnabled(ctx)) runCatching { msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComp, main) }
        renderNotifs()
        pickController()
        main.removeCallbacks(mediaTick); main.post(mediaTick)
    }

    override fun onHide() {
        shown = false
        NotifListener.watchers -= notifWatcher
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.let { runCatching { it.unregisterCallback(mediaCb) } }
        controller = null
        main.removeCallbacks(mediaTick)
    }

    fun destroy() {
        main.removeCallbacks(tick)
        runCatching { cam.unregisterTorchCallback(torchCb) }
    }

    private fun openSettings(i: Intent) {
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        close()
    }

    private fun firePending(pi: PendingIntent?) {
        if (pi == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("DEPRECATION")
                val opts = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, null, null, null, null, opts.toBundle())
            } else pi.send()
        }
        close()
    }

    // ---------- Profil-Kurzinfo ----------

    private fun profileCard() = ui.tile().apply {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        profAvatar = TextView(ctx).apply {
            gravity = Gravity.CENTER; setTextColor(Holo.NAVY); typeface = Holo.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            background = ui.cut(Holo.ICE, Holo.ICE, 10f)
        }
        row.addView(profAvatar, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)).apply { marginEnd = ui.dp(14) })
        val texts = ui.column()
        profName = ui.title("")
        profRole = ui.small("")
        texts.addView(profName); texts.addView(profRole)
        row.addView(texts, ui.weight())
        row.addView(ui.small("Profil ›").apply { setTextColor(Holo.ICE) })
        addView(row)
        setOnClickListener { openProfile() }
    }

    private fun renderProfile() {
        val name = Prefs.profile(ctx, "name")
        profName.text = name.ifBlank { "Dein Profil" }
        profRole.text = Prefs.profile(ctx, "role").ifBlank { "Tippen, um dein Profil einzurichten" }
        profAvatar.text = initials(name)
    }

    // ---------- Fokus-Timer ----------

    private fun today() = java.time.LocalDate.now().toEpochDay()

    private fun timerTile() = ui.tile().apply {
        addView(ui.label("Fokus · tiefe Arbeit"))
        timerText = ui.big("25:00").apply {
            setOnClickListener {
                if (!timerRunning) { presetIdx = (presetIdx + 1) % presets.size; timerLeft = presets[presetIdx] * 60; updateTimer() }
            }
        }
        addView(timerText)
        timerBtn = ui.pill("Start") { toggleTimer() }
        addView(ui.rowOf(timerBtn, ui.pill("↺") { resetTimer() }), ui.lpMatch(top = 8))
        focusInfo = ui.small("")
        addView(focusInfo, ui.lpMatch(top = 8))
    }

    private fun toggleTimer() {
        if (timerRunning) { timerRunning = false; main.removeCallbacks(tick) }
        else { if (timerLeft <= 0) timerLeft = presets[presetIdx] * 60; timerRunning = true; main.postDelayed(tick, 1000) }
        updateTimer()
    }

    private fun resetTimer() {
        timerRunning = false; main.removeCallbacks(tick); timerLeft = presets[presetIdx] * 60; updateTimer()
    }

    private fun updateTimer() {
        if (!::timerText.isInitialized) return
        timerText.text = if (timerLeft <= 0) "Fertig" else "%02d:%02d".format(timerLeft / 60, timerLeft % 60)
        timerBtn.text = if (timerRunning) "PAUSE" else "START"
        ui.setActive(timerBtn, timerRunning)
        val n = Prefs.focusCount(ctx, today())
        focusInfo.text = "Zeit antippen: 25 / 50 / 90 / 5 Min. · Heute: $n " + if (n == 1) "Sitzung" else "Sitzungen"
    }

    private fun timerDone() {
        timerRunning = false; timerLeft = 0
        if (presets[presetIdx] >= 25) Prefs.addFocus(ctx, today())
        updateTimer()
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else ctx.getSystemService(Vibrator::class.java)
        vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
        runCatching {
            ctx.getSystemService(NotificationManager::class.java).notify(2,
                Notification.Builder(ctx, OverlayService.CHANNEL_TIMER)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("Fokus-Sitzung beendet")
                    .setContentText("${presets[presetIdx]} Minuten sind um.")
                    .setAutoCancel(true).build())
        }
    }

    // ---------- Musik ----------

    private fun mediaTileView() = ui.tile().apply {
        mediaTile = this
        visibility = View.GONE
        mediaApp = ui.label("Wiedergabe")
        addView(mediaApp)
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(10), 0, 0) }
        mediaArt = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = ui.cut(Holo.PANEL_DEEP, Holo.LINE, 10f) }
        line.addView(mediaArt, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)).apply { marginEnd = ui.dp(14) })
        val texts = ui.column()
        mediaTitle = ui.body("", 17f).also { ui.ellipsize(it, 1) }
        mediaArtist = ui.small("").also { ui.ellipsize(it, 1) }
        texts.addView(mediaTitle); texts.addView(mediaArtist)
        line.addView(texts, ui.weight())
        line.setOnClickListener { firePending(controller?.sessionActivity) }
        addView(line)
        mediaBar = ui.bar().apply { max = 1000 }
        addView(mediaBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(4)).apply { topMargin = ui.dp(12) })
        val ctrls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val size = ui.dp(52)
        ctrls.addView(ui.iconBtn(R.drawable.ic_prev, "Zurück") { controller?.transportControls?.skipToPrevious() }, LinearLayout.LayoutParams(size, size))
        mediaPlay = ui.iconBtn(R.drawable.ic_play, "Abspielen/Pause") {
            val c = controller ?: return@iconBtn
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
        }
        ctrls.addView(mediaPlay, LinearLayout.LayoutParams(ui.dp(64), size).apply { marginStart = ui.dp(14); marginEnd = ui.dp(14) })
        ctrls.addView(ui.iconBtn(R.drawable.ic_next, "Weiter") { controller?.transportControls?.skipToNext() }, LinearLayout.LayoutParams(size, size))
        addView(ctrls, ui.lpMatch(top = 12))
    }

    private fun pickController() {
        if (!shown) return
        val list = if (NotifListener.isEnabled(ctx)) runCatching { msm.getActiveSessions(listenerComp) }.getOrDefault(emptyList()) else emptyList()
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
        val appName = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString() }
            .getOrDefault("Wiedergabe")
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        mediaApp.text = ((if (playing) "▶ " else "❚❚ ") + appName).uppercase()
        mediaTitle.text = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unbekannter Titel"
        mediaArtist.text = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art != null) mediaArt.setImageBitmap(art) else mediaArt.setImageDrawable(null)
        mediaPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        updateMediaBar()
    }

    private fun updateMediaBar() {
        val c = controller ?: return
        val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val st = c.playbackState ?: return
        if (dur <= 0) { mediaBar.progress = 0; return }
        var pos = st.position
        if (st.state == PlaybackState.STATE_PLAYING) pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        mediaBar.progress = ((pos.coerceIn(0, dur) * 1000) / dur).toInt()
    }

    // ---------- Benachrichtigungen ----------

    private fun notifTileView() = ui.tile().apply {
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.label("Benachrichtigungen"), ui.weight())
        notifClear = ui.pill("Alle löschen") { runCatching { NotifListener.instance?.cancelAllNotifications() } }
        head.addView(notifClear)
        addView(head)
        notifBox = ui.column()
        addView(notifBox)
    }

    private fun renderNotifs() {
        notifBox.removeAllViews()
        if (!NotifListener.isEnabled(ctx)) {
            notifClear.visibility = View.GONE
            notifBox.addView(ui.small("Erlaube den Benachrichtigungszugriff, dann siehst du hier Nachrichten und kannst Spotify steuern."), ui.lpMatch(top = 8))
            notifBox.addView(ui.pill("Zugriff erlauben") { openSettings(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, ui.lpWrap(top = 10))
            return
        }
        val all = runCatching { NotifListener.instance?.activeNotifications?.toList() }.getOrNull() ?: emptyList()
        val list = all.filter { sbn ->
            val n = sbn.notification
            sbn.packageName != ctx.packageName && (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                !sbn.isOngoing && !n.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank()
        }.sortedByDescending { it.postTime }
        notifClear.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (list.isEmpty()) { notifBox.addView(ui.small("Keine neuen Benachrichtigungen."), ui.lpMatch(top = 10)); return }
        list.take(6).forEach { notifBox.addView(notifRow(it)) }
        if (list.size > 6) notifBox.addView(ui.small("+ ${list.size - 6} weitere"), ui.lpMatch(top = 8))
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
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.dp(10), 0, ui.dp(10))
            setOnClickListener { firePending(n.contentIntent) }
        }
        val icon = ImageView(ctx).apply {
            val d = runCatching { n.smallIcon?.loadDrawable(ctx) }.getOrNull()
            if (d != null) { setImageDrawable(d); imageTintList = ColorStateList.valueOf(Holo.ICE) }
            else runCatching { setImageDrawable(pm.getApplicationIcon(sbn.packageName)) }
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            background = ui.cut(Holo.PANEL_DEEP, Holo.LINE, 8f)
        }
        line.addView(icon, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(12) })
        val c = ui.column()
        c.addView(ui.label("$app · $time").apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); ui.ellipsize(this, 1) })
        c.addView(ui.body(title).also { ui.ellipsize(it, 1) })
        if (text.isNotBlank()) c.addView(ui.small(text).also { ui.ellipsize(it, 2) })
        line.addView(c, ui.weight())
        if (sbn.isClearable) {
            line.addView(TextView(ctx).apply {
                this.text = "×"; contentDescription = "Entfernen"
                setTextColor(Holo.ICE_DIM); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
                setOnClickListener { runCatching { NotifListener.instance?.cancelNotification(sbn.key) } }
            }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        }
        val wrap = ui.column()
        wrap.addView(ui.hline(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)))
        wrap.addView(line)
        return wrap
    }

    // ---------- Schalter ----------

    private fun toggleRow(): View {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val rowView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(icon: Int, name: String, isOn: () -> Boolean, onTap: () -> Unit) {
            val c = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val img = ImageView(ctx).apply { setImageResource(icon) }
            val b = FrameLayout(ctx).apply {
                contentDescription = name
                setOnClickListener { onTap(); refreshToggles() }
                addView(img, FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER))
            }
            c.addView(b, LinearLayout.LayoutParams(ui.dp(58), ui.dp(58)))
            c.addView(ui.label(name).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0); setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setTextColor(Holo.TEXT_DIM); maxLines = 1 })
            rowView.addView(c, ui.weight())
            toggleViews += Triple(b, img, isOn)
        }
        add(R.drawable.ic_wifi, "WLAN", { runCatching { ctx.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrDefault(false) }) {
            openSettings(if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        add(R.drawable.ic_bt, "Bluetooth", { runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(false) }) {
            openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        add(R.drawable.ic_dnd, "Ruhe", { nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL }) {
            if (nm.isNotificationPolicyAccessGranted) {
                val on = nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
                nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else openSettings(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        add(R.drawable.ic_flash, "Lampe", { torchOn }) { torchId?.let { id -> runCatching { cam.setTorchMode(id, !torchOn) } } }
        return rowView
    }

    private fun refreshToggles() {
        main.postDelayed({
            toggleViews.forEach { (b, img, isOn) ->
                val on = runCatching { isOn() }.getOrDefault(false)
                b.background = if (on) ui.cut(Holo.ICE, Holo.ICE, 12f) else ui.cut(Holo.PANEL_DEEP, Holo.LINE, 12f)
                img.imageTintList = ColorStateList.valueOf(if (on) Holo.NAVY else Holo.ICE)
            }
        }, 150)
    }
}

fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "◆"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts.last().take(1)).uppercase()
    }
}
