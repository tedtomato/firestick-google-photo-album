package app.photoframe.tv

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.bumptech.glide.MemoryCategory
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

class SlideshowActivity : ComponentActivity() {

    /** One photo's area: a blurred backdrop with the photo on top. */
    private class Pane(val root: FrameLayout, val backdrop: ImageView, val photo: ImageView)

    /**
     * Two of these are stacked; the hidden one loads the next slide, then they swap.
     * A slide is one photo, or two portrait photos side by side (the second pane and the gap are hidden otherwise),
     * or one video, which plays in [video] on top of its poster frame.
     */
    private class Slot(val root: LinearLayout, val panes: List<Pane>, val gap: View, val video: TextureView)

    /** Where a photo's slow zoom or Ken Burns drift is heading, so it can resume after a pause. */
    private class Motion(val scale: Float, val dx: Float, val dy: Float)

    private lateinit var library: Library
    private val settings get() = library.settings

    private lateinit var slots: Array<Slot>
    private var front = 0
    private var gapPx = 0
    private lateinit var status: TextView
    private lateinit var clock: TextView
    private lateinit var photoDates: List<TextView>
    private lateinit var pausedBadge: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pool: List<Photo> = emptyList()
    private val queue = ArrayDeque<Photo>()
    private val history = ArrayList<List<Photo>>()
    private var historyIndex = -1
    private var current: List<Photo>? = null
    private var started = false
    private var paused = false
    private var loadToken = 0
    private var failures = 0
    private var refreshJob: Job? = null
    private var motions: List<Pair<View, Motion>> = emptyList()
    private var player: ExoPlayer? = null
    /** The slot whose video is playing, if any. */
    private var videoSlot: Slot? = null
    private var videoUrls: List<String> = emptyList()
    private var videoAttempt = 0

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) showNext()
        }

        override fun onPlayerError(error: PlaybackException) {
            // Try the next version of the video (e.g. 720p when there is no 1080p one).
            if (videoSlot != null && videoAttempt + 1 < videoUrls.size) {
                playVideoUrl(videoAttempt + 1)
                return
            }
            // Leave the poster frame up for one normal interval, then move on.
            handler.removeCallbacks(advance)
            handler.postDelayed(advance, settings.intervalSeconds * 1000L)
        }

        override fun onRenderedFirstFrame() {
            videoSlot?.video?.animate()?.alpha(1f)?.setDuration(400)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            videoSlot?.let { fitVideo(it.video, videoSize) }
        }
    }

    private val advance = Runnable { showNext() }

    private val tickClock = object : Runnable {
        override fun run() {
            clock.text = android.text.format.DateFormat.getTimeFormat(this@SlideshowActivity).format(Date())
            handler.postDelayed(this, 10_000)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Library.KEY_VERSION -> reloadPool()
            Settings.KEY_INTERVAL -> scheduleNext()
            Settings.KEY_SHUFFLE -> queue.clear()
            Settings.KEY_CLOCK, Settings.KEY_DATE -> applyOverlays()
            Settings.KEY_VIDEOS -> reloadPool()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_slideshow)
        library = Library.get(this)
        // Photos are loaded a bit larger than the screen (see loadScale), so let Glide keep more in memory.
        Glide.get(this).setMemoryCategory(MemoryCategory.HIGH)

        gapPx = (6 * resources.displayMetrics.density).roundToInt()
        val stage = findViewById<FrameLayout>(R.id.stage)
        slots = arrayOf(createSlot(stage), createSlot(stage))
        status = findViewById(R.id.status)
        clock = findViewById(R.id.clock)
        photoDates = listOf(findViewById(R.id.photo_date), findViewById(R.id.photo_date_2))
        pausedBadge = findViewById(R.id.paused)
    }

    private fun createSlot(stage: FrameLayout): Slot {
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val panes = List(2) {
            val backdrop = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setColorFilter(0xFF707070.toInt(), PorterDuff.Mode.MULTIPLY)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val photo = ImageView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val root = FrameLayout(this).apply {
                addView(backdrop, match, match)
                addView(photo, match, match)
            }
            Pane(root, backdrop, photo)
        }
        val video = TextureView(this).apply { visibility = View.GONE }
        panes[0].root.addView(video, FrameLayout.LayoutParams(match, match, Gravity.CENTER))
        val gap = View(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            visibility = View.INVISIBLE
            addView(panes[0].root, LinearLayout.LayoutParams(0, match, 1f))
            addView(gap, LinearLayout.LayoutParams(gapPx, match))
            addView(panes[1].root, LinearLayout.LayoutParams(0, match, 1f))
        }
        stage.addView(root, match, match)
        return Slot(root, panes, gap, video)
    }

    override fun onStart() {
        super.onStart()
        started = true
        library.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        applyOverlays()
        reloadPool()
        if (library.albums().isEmpty() && !promptedForSetup) {
            promptedForSetup = true
            openSettings()
            return
        }
        startRefreshLoop()
        player = ExoPlayer.Builder(this).build().apply { addListener(playerListener) }
        // A video stopped when the screen went away; move on rather than restart it.
        if (current == null || isVideo(current)) showNext() else scheduleNext()
    }

    override fun onStop() {
        started = false
        library.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacksAndMessages(null)
        refreshJob?.cancel()
        stopVideo()
        player?.release()
        player = null
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> showNext()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> showPrevious()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> togglePause()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> openSettings()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun togglePause() {
        paused = !paused
        pausedBadge.visibility = if (paused) View.VISIBLE else View.GONE
        if (isVideo(current)) player?.playWhenReady = !paused
        if (paused) {
            handler.removeCallbacks(advance)
            for ((view, _) in motions) view.animate().cancel() // freezes the zoom where it is
        } else {
            scheduleNext()
            for ((view, motion) in motions) startMotion(view, motion, settings.intervalSeconds * 1000L)
        }
    }

    private fun startRefreshLoop() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                if (System.currentTimeMillis() >= library.lastRefresh + settings.refreshMinutes * 60_000L) {
                    library.refreshAll()
                }
                delay(60_000)
            }
        }
    }

    // ---- What to show next ----

    private fun reloadPool() {
        val photos = library.photos().filter { !it.isVideo || settings.videos != "off" }
        val oldIds = pool.mapTo(HashSet()) { it.id }
        val newIds = photos.mapTo(HashSet()) { it.id }
        pool = photos

        queue.retainAll { it.id in newIds }
        if (history.any { slide -> slide.any { it.id !in newIds } }) {
            history.retainAll { slide -> slide.all { it.id in newIds } }
            historyIndex = history.size - 1
        }
        // Mix newly added photos into the rest of this round so they show up soon.
        if (oldIds.isNotEmpty()) {
            for (photo in photos) {
                if (photo.id in oldIds) continue
                val at = if (settings.shuffle) Random.nextInt(queue.size + 1) else queue.size
                queue.add(at, photo)
            }
        }

        if (current == null && pool.isNotEmpty() && started && !paused) showNext() else updateStatus()
    }

    private fun refillQueue() {
        if (pool.isEmpty()) return
        queue.addAll(if (settings.shuffle) pool.shuffled() else pool)
        // Don't show the same photo twice in a row across rounds.
        val shown = current.orEmpty()
        if (queue.size > 1 && shown.any { it.id == queue.first().id }) queue.add(queue.removeAt(0))
    }

    /** The slide the queue gives next: its first photo, plus the next portrait photo if that one is a portrait too. */
    private fun upcomingSlide(consume: Boolean): List<Photo>? {
        if (queue.isEmpty()) refillQueue()
        val first = queue.firstOrNull() ?: return null
        var partner = -1
        if (canPair(first)) {
            for (i in 1 until queue.size) {
                if (queue[i].id != first.id && canPair(queue[i])) {
                    partner = i
                    break
                }
            }
        }
        val slide = if (partner > 0) listOf(first, queue[partner]) else listOf(first)
        if (consume) {
            if (partner > 0) queue.removeAt(partner)
            queue.removeAt(0)
        }
        return slide
    }

    private fun canPair(photo: Photo): Boolean {
        if (!settings.pairPortraits) return false
        val (w, h) = stageSize()
        return w > h && !photo.isVideo && photo.width > 0 && photo.height > photo.width * 1.1f
    }

    private fun showNext() {
        handler.removeCallbacks(advance)
        if (historyIndex < history.size - 1) {
            historyIndex++
            display(history[historyIndex], backwards = false)
            return
        }
        val next = upcomingSlide(consume = true)
        if (next == null) {
            updateStatus()
            return
        }
        history.add(next)
        if (history.size > 500) history.removeAt(0)
        historyIndex = history.size - 1
        display(next, backwards = false)
    }

    private fun showPrevious() {
        if (historyIndex <= 0) return
        historyIndex--
        display(history[historyIndex], backwards = true)
    }

    private fun peekNext(): List<Photo>? =
        if (historyIndex < history.size - 1) history[historyIndex + 1] else upcomingSlide(consume = false)

    // ---- Loading ----

    private fun stageSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels.coerceAtLeast(640) to metrics.heightPixels.coerceAtLeast(360)
    }

    /** The size of each photo's area: the whole screen, or half of it (minus the gap) for a pair. */
    private fun paneSize(paired: Boolean): Pair<Int, Int> {
        val (w, h) = stageSize()
        return if (paired) (w - gapPx) / 2 to h else w to h
    }

    private fun shouldCrop(photo: Photo, w: Int, h: Int): Boolean = when {
        photo.isVideo -> false // shown whole, so the poster frame lines up with the video
        settings.fitMode == "fill" -> true
        settings.fitMode == "smart" -> {
            val area = w.toFloat() / h
            abs(photo.aspect - area) / area < 0.2f
        }
        else -> false
    }

    /** How far photos zoom while on screen, at most (1 = they don't move). */
    private fun maxZoom(): Float = when {
        settings.transition == "kenburns" || settings.transition == "random" -> KEN_BURNS_SCALE
        settings.slowZoom -> slowZoomScale()
        else -> 1f
    }

    /**
     * How many bitmap pixels to load per screen pixel. A photo shown at about 1:1 that slowly zooms
     * shimmers (moiré) on fine detail, so moving photos are loaded 1.3–1.5× sharper than the screen and
     * scaled down with mipmaps. Photos without that much detail are loaded a bit softer instead, so they
     * are always enlarged by at least 1.3×, which doesn't shimmer either.
     */
    private fun loadScale(photo: Photo, w: Int, h: Int, crop: Boolean): Float {
        val zoom = maxZoom()
        if (zoom <= 1f) return 1f
        val wanted = 1.3f * zoom
        if (photo.width <= 0 || photo.height <= 0) return wanted
        val sx = photo.width.toFloat() / w
        val sy = photo.height.toFloat() / h
        val available = if (crop) minOf(sx, sy) else maxOf(sx, sy)
        return if (available >= wanted) wanted else 0.75f
    }

    /** Loads the photo for a w×h area; the ImageView does the final fit or crop on the GPU. */
    private fun photoRequest(photo: Photo, w: Int, h: Int, crop: Boolean): RequestBuilder<Drawable> {
        val scale = loadScale(photo, w, h, crop)
        var loadW = (w * scale).roundToInt()
        var loadH = (h * scale).roundToInt()
        val cap = MAX_LOAD_SIDE.toFloat() / maxOf(loadW, loadH)
        if (cap < 1f) {
            loadW = (loadW * cap).roundToInt()
            loadH = (loadH * cap).roundToInt()
        }
        return Glide.with(this).load(photo.sizedUrl(loadW, loadH, crop))
            .override(loadW, loadH)
            .downsample(DownsampleStrategy.CENTER_INSIDE)
            .dontTransform()
    }

    /** A tiny version of the photo, stretched across its area, makes a soft blurred background. */
    private fun backdropRequest(photo: Photo): RequestBuilder<Drawable> =
        Glide.with(this).load(photo.sizedUrl(96, 96)).override(64, 36).centerCrop()

    private fun display(slide: List<Photo>, backwards: Boolean) {
        handler.removeCallbacks(advance)
        val token = ++loadToken
        val target = slots[1 - front]
        val paired = slide.size > 1
        val (w, h) = paneSize(paired)
        target.gap.visibility = if (paired) View.VISIBLE else View.GONE
        target.panes[1].root.visibility = if (paired) View.VISIBLE else View.GONE
        var pending = slide.size
        var failed = false

        for ((index, pane) in target.panes.withIndex()) {
            val photo = slide.getOrNull(index)
            if (photo == null) {
                // Free the bitmaps of a pane this slide doesn't use.
                Glide.with(this).clear(pane.photo)
                Glide.with(this).clear(pane.backdrop)
                continue
            }
            val crop = shouldCrop(photo, w, h)
            pane.photo.scaleType = if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            photoRequest(photo, w, h, crop)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean,
                    ): Boolean {
                        handler.post {
                            if (token == loadToken && !failed) {
                                failed = true
                                onPhotoFailed()
                            }
                        }
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean,
                    ): Boolean {
                        // Smooth scaling down to the screen, so zooming doesn't shimmer.
                        (resource as? BitmapDrawable)?.bitmap?.setHasMipMap(true)
                        handler.post { if (token == loadToken && !failed && --pending == 0) reveal(slide, backwards) }
                        return false
                    }
                })
                .into(pane.photo)

            if (crop) {
                Glide.with(this).clear(pane.backdrop)
                pane.backdrop.setImageDrawable(null)
            } else {
                backdropRequest(photo).into(pane.backdrop)
            }
        }
    }

    private fun onPhotoFailed() {
        failures++
        if (failures >= pool.size.coerceIn(1, 8)) {
            status.setText(R.string.status_offline)
            status.visibility = View.VISIBLE
            handler.postDelayed(advance, 30_000)
        } else {
            handler.postDelayed(advance, 300)
        }
    }

    private fun preloadNext() {
        val next = peekNext() ?: return
        val (w, h) = paneSize(next.size > 1)
        for (photo in next) {
            val crop = shouldCrop(photo, w, h)
            photoRequest(photo, w, h, crop).preload()
            if (!crop) backdropRequest(photo).preload(64, 36)
        }
    }

    // ---- Showing ----

    private fun reveal(slide: List<Photo>, backwards: Boolean) {
        val outgoing = slots[front]
        front = 1 - front
        val incoming = slots[front]
        current = slide
        failures = 0
        status.visibility = View.GONE
        for ((index, label) in photoDates.withIndex()) {
            val takenAt = slide.getOrNull(index)?.takenAt ?: 0L
            label.text = if (takenAt > 0) {
                java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(Date(takenAt))
            } else {
                ""
            }
        }
        stopVideo()
        animateSwap(outgoing, incoming, slide.size, backwards)
        if (isVideo(slide)) startVideo(incoming, slide[0])
        scheduleNext()
        preloadNext()
    }

    private fun transitionMillis(): Long = (settings.intervalSeconds * 300L).coerceIn(400L, 1500L)

    private fun scheduleNext() {
        handler.removeCallbacks(advance)
        if (started && !paused && current != null) {
            // A video moves on when it ends; this is only a fallback if it stalls.
            val delay = if (isVideo(current)) MAX_VIDEO_MS else settings.intervalSeconds * 1000L + transitionMillis()
            handler.postDelayed(advance, delay)
        }
    }

    private fun animateSwap(outgoing: Slot, incoming: Slot, photoCount: Int, backwards: Boolean) {
        val duration = transitionMillis()
        val incomingPhotos = incoming.panes.take(photoCount).map { it.photo }
        for (view in listOf<View>(outgoing.root, incoming.root) + incoming.panes.map { it.photo }) {
            view.animate().cancel()
            reset(view)
        }
        incoming.root.translationZ = 1f
        outgoing.root.translationZ = 0f
        incoming.root.visibility = View.VISIBLE

        val hideOutgoing = Runnable {
            outgoing.root.visibility = View.INVISIBLE
            if (outgoing !== videoSlot) outgoing.video.visibility = View.GONE
            reset(outgoing.root)
            for (pane in outgoing.panes) {
                pane.photo.animate().cancel()
                reset(pane.photo)
            }
        }

        var type = settings.transition
        if (type == "random") type = listOf("fade", "kenburns", "slide", "zoom", "black").random()

        when (type) {
            "slide" -> {
                val direction = if (backwards) -1 else 1
                val width = (incoming.root.parent as View).width.toFloat()
                incoming.root.translationX = direction * width
                incoming.root.animate().translationX(0f).setDuration(duration)
                    .setInterpolator(DecelerateInterpolator(1.5f)).withEndAction(hideOutgoing)
                outgoing.root.animate().translationX(-direction * width).setDuration(duration)
                    .setInterpolator(DecelerateInterpolator(1.5f))
            }
            "zoom" -> {
                incoming.root.alpha = 0f
                incoming.root.scaleX = 1.25f
                incoming.root.scaleY = 1.25f
                incoming.root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration)
                    .setInterpolator(DecelerateInterpolator()).withEndAction(hideOutgoing)
            }
            "black" -> {
                incoming.root.alpha = 0f
                outgoing.root.animate().alpha(0f).setDuration(duration / 2)
                    .setInterpolator(LinearInterpolator()).withEndAction {
                        incoming.root.animate().alpha(1f).setDuration(duration / 2)
                            .setInterpolator(LinearInterpolator()).withEndAction(hideOutgoing)
                    }
            }
            else -> { // "fade" and "kenburns"
                incoming.root.alpha = 0f
                incoming.root.animate().alpha(1f).setDuration(duration)
                    .setInterpolator(LinearInterpolator()).withEndAction(hideOutgoing)
            }
        }

        val (w, h) = paneSize(photoCount > 1)
        motions = when {
            isVideo(current) -> emptyList()
            type == "kenburns" -> incomingPhotos.map { it to kenBurns(it, w, h) }
            settings.slowZoom -> incomingPhotos.map { it to Motion(slowZoomScale(), 0f, 0f) }
            else -> emptyList()
        }
        for ((view, motion) in motions) startMotion(view, motion, photoMillis())
    }

    /** How long a photo is on screen, from the start of its transition in to the end of the one out. */
    private fun photoMillis() = settings.intervalSeconds * 1000L + transitionMillis() * 2

    /** A few percent, at a steady, barely noticeable pace while the photo is on screen. */
    private fun slowZoomScale() = 1f + (photoMillis() / 1000f * 0.005f).coerceIn(0.03f, 0.08f)

    private fun startMotion(view: View, target: Motion, durationMs: Long) {
        if (paused) return
        view.animate()
            .scaleX(target.scale).scaleY(target.scale)
            .translationX(target.dx).translationY(target.dy)
            .setDuration(durationMs)
            .setInterpolator(LinearInterpolator())
    }

    /**
     * Slow zoom with a gentle drift; the drift never exceeds the zoom margin, so no edges show.
     * Sets the starting point on [view] and returns where it should end up.
     */
    private fun kenBurns(view: View, w: Int, h: Int): Motion {
        val dx = (Random.nextFloat() * 2 - 1) * 0.06f * w
        val dy = (Random.nextFloat() * 2 - 1) * 0.06f * h
        val zoomIn = Random.nextBoolean()
        view.scaleX = if (zoomIn) 1f else KEN_BURNS_SCALE
        view.scaleY = view.scaleX
        view.translationX = if (zoomIn) 0f else dx
        view.translationY = if (zoomIn) 0f else dy
        return if (zoomIn) Motion(KEN_BURNS_SCALE, dx, dy) else Motion(1f, 0f, 0f)
    }

    private fun reset(view: View) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    // ---- Videos ----

    private fun isVideo(slide: List<Photo>?) = slide?.firstOrNull()?.isVideo == true

    private fun startVideo(slot: Slot, video: Photo) {
        val player = player ?: return
        videoSlot = slot
        slot.video.animate().cancel()
        slot.video.alpha = 0f // fades in over the poster frame once the first frame is ready
        slot.video.visibility = View.VISIBLE
        val withSound = settings.videos == "sound"
        player.setAudioAttributes(VIDEO_AUDIO, withSound)
        player.volume = if (withSound) 1f else 0f
        player.setVideoTextureView(slot.video)
        videoUrls = video.videoUrls()
        playVideoUrl(0)
    }

    private fun playVideoUrl(attempt: Int) {
        val player = player ?: return
        videoAttempt = attempt
        player.setMediaItem(MediaItem.fromUri(videoUrls[attempt]))
        player.prepare()
        player.playWhenReady = !paused
    }

    /** Stops playback; the video view keeps its last frame until the transition hides it. */
    private fun stopVideo() {
        val slot = videoSlot ?: return
        videoSlot = null
        player?.run {
            stop()
            clearMediaItems()
            clearVideoTextureView(slot.video)
        }
    }

    /** Sizes the video view to show the whole video, centred in its pane like the poster frame. */
    private fun fitVideo(view: TextureView, size: VideoSize) {
        if (size.width <= 0 || size.height <= 0) return
        val aspect = size.width * size.pixelWidthHeightRatio / size.height
        val (w, h) = paneSize(false)
        val (videoW, videoH) = if (aspect > w.toFloat() / h) {
            w to (w / aspect).roundToInt()
        } else {
            (h * aspect).roundToInt() to h
        }
        view.layoutParams = FrameLayout.LayoutParams(videoW, videoH, Gravity.CENTER)
    }

    // ---- Overlays ----

    private fun applyOverlays() {
        handler.removeCallbacks(tickClock)
        clock.visibility = if (settings.showClock) View.VISIBLE else View.GONE
        if (settings.showClock) tickClock.run()
        for (label in photoDates) label.visibility = if (settings.showDate) View.VISIBLE else View.GONE
    }

    private fun updateStatus() {
        if (current != null) {
            status.visibility = View.GONE
            return
        }
        val albums = library.albums()
        val stillLoading = albums.any { library.isFetching(it.link) || (it.updatedAt == 0L && it.error == null) }
        status.text = when {
            albums.isEmpty() -> getString(R.string.status_no_albums)
            pool.isEmpty() && !stillLoading ->
                getString(R.string.status_no_photos, albums.firstNotNullOfOrNull { it.error }.orEmpty())
            else -> getString(R.string.status_loading)
        }
        status.visibility = View.VISIBLE
    }

    private companion object {
        /** Only jump to settings automatically once per launch, so Back from settings still works. */
        var promptedForSetup = false

        const val KEN_BURNS_SCALE = 1.15f

        /** Keeps the loaded bitmap within what old Fire TV sticks handle comfortably. */
        const val MAX_LOAD_SIDE = 3072

        /** Moves on from a video that hasn't ended by then, e.g. because it stalled. */
        const val MAX_VIDEO_MS = 5 * 60_000L

        val VIDEO_AUDIO: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
    }
}
