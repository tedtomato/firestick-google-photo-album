package app.photoframe.tv

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.abs
import kotlin.random.Random

class SlideshowActivity : ComponentActivity() {

    /** Two of these are stacked; the hidden one loads the next photo, then they swap. */
    private class Slot(val root: FrameLayout, val backdrop: ImageView, val photo: ImageView)

    /** Where the front photo's slow zoom or Ken Burns drift is heading, so it can resume after a pause. */
    private class Motion(val scale: Float, val dx: Float, val dy: Float)

    private lateinit var library: Library
    private val settings get() = library.settings

    private lateinit var slots: Array<Slot>
    private var front = 0
    private lateinit var status: TextView
    private lateinit var clock: TextView
    private lateinit var photoDate: TextView
    private lateinit var pausedBadge: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var pool: List<Photo> = emptyList()
    private val queue = ArrayDeque<Photo>()
    private val history = ArrayList<Photo>()
    private var historyIndex = -1
    private var current: Photo? = null
    private var started = false
    private var paused = false
    private var loadToken = 0
    private var failures = 0
    private var refreshJob: Job? = null
    private var motion: Motion? = null

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
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_slideshow)
        library = Library.get(this)

        slots = arrayOf(
            Slot(findViewById(R.id.slot_a), findViewById(R.id.backdrop_a), findViewById(R.id.photo_a)),
            Slot(findViewById(R.id.slot_b), findViewById(R.id.backdrop_b), findViewById(R.id.photo_b)),
        )
        for (slot in slots) slot.backdrop.setColorFilter(0xFF707070.toInt(), PorterDuff.Mode.MULTIPLY)
        status = findViewById(R.id.status)
        clock = findViewById(R.id.clock)
        photoDate = findViewById(R.id.photo_date)
        pausedBadge = findViewById(R.id.paused)
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
        if (current == null) showNext() else scheduleNext()
    }

    override fun onStop() {
        started = false
        library.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacksAndMessages(null)
        refreshJob?.cancel()
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
        val photo = slots[front].photo
        if (paused) {
            handler.removeCallbacks(advance)
            photo.animate().cancel() // freezes the zoom where it is
        } else {
            scheduleNext()
            motion?.let { startMotion(photo, it, settings.intervalSeconds * 1000L) }
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
        val photos = library.photos()
        val oldIds = pool.mapTo(HashSet()) { it.id }
        val newIds = photos.mapTo(HashSet()) { it.id }
        pool = photos

        queue.retainAll { it.id in newIds }
        if (history.any { it.id !in newIds }) {
            history.retainAll { it.id in newIds }
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
        if (queue.size > 1 && queue.first().id == current?.id) queue.add(queue.removeAt(0))
    }

    private fun showNext() {
        handler.removeCallbacks(advance)
        if (historyIndex < history.size - 1) {
            historyIndex++
            display(history[historyIndex], backwards = false)
            return
        }
        if (queue.isEmpty()) refillQueue()
        val next = queue.removeFirstOrNull()
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

    private fun peekNext(): Photo? {
        if (historyIndex < history.size - 1) return history[historyIndex + 1]
        if (queue.isEmpty()) refillQueue()
        return queue.firstOrNull()
    }

    // ---- Loading ----

    private fun screenSize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        var w = metrics.widthPixels.coerceAtLeast(640)
        var h = metrics.heightPixels.coerceAtLeast(360)
        if (w > 2560) {
            h = h * 2560 / w
            w = 2560
        }
        return w to h
    }

    private fun shouldCrop(photo: Photo, w: Int, h: Int): Boolean = when (settings.fitMode) {
        "fill" -> true
        "smart" -> {
            val screen = w.toFloat() / h
            abs(photo.aspect - screen) / screen < 0.2f
        }
        else -> false
    }

    private fun photoRequest(photo: Photo): RequestBuilder<Drawable> {
        val (w, h) = screenSize()
        val crop = shouldCrop(photo, w, h)
        val request = Glide.with(this).load(photo.sizedUrl(w, h, crop)).override(w, h)
        return if (crop) request.centerCrop() else request.fitCenter()
    }

    /** A tiny version of the photo, stretched across the screen, makes a soft blurred background. */
    private fun backdropRequest(photo: Photo): RequestBuilder<Drawable> =
        Glide.with(this).load(photo.sizedUrl(96, 96)).override(64, 36).centerCrop()

    private fun display(photo: Photo, backwards: Boolean) {
        handler.removeCallbacks(advance)
        val token = ++loadToken
        val target = slots[1 - front]
        val (w, h) = screenSize()
        val crop = shouldCrop(photo, w, h)
        target.photo.scaleType = if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER

        photoRequest(photo)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean,
                ): Boolean {
                    handler.post { if (token == loadToken) onPhotoFailed() }
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean,
                ): Boolean {
                    handler.post { if (token == loadToken) reveal(photo, backwards) }
                    return false
                }
            })
            .into(target.photo)

        if (crop) {
            Glide.with(this).clear(target.backdrop)
            target.backdrop.setImageDrawable(null)
        } else {
            backdropRequest(photo).into(target.backdrop)
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
        val (w, h) = screenSize()
        photoRequest(next).preload(w, h)
        if (!shouldCrop(next, w, h)) backdropRequest(next).preload(64, 36)
    }

    // ---- Showing ----

    private fun reveal(photo: Photo, backwards: Boolean) {
        val outgoing = slots[front]
        front = 1 - front
        val incoming = slots[front]
        current = photo
        failures = 0
        status.visibility = View.GONE
        photoDate.text = if (photo.takenAt > 0) {
            java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(Date(photo.takenAt))
        } else {
            ""
        }
        animateSwap(outgoing, incoming, backwards)
        scheduleNext()
        preloadNext()
    }

    private fun transitionMillis(): Long = (settings.intervalSeconds * 300L).coerceIn(400L, 1500L)

    private fun scheduleNext() {
        handler.removeCallbacks(advance)
        if (started && !paused && current != null) {
            handler.postDelayed(advance, settings.intervalSeconds * 1000L + transitionMillis())
        }
    }

    private fun animateSwap(outgoing: Slot, incoming: Slot, backwards: Boolean) {
        val duration = transitionMillis()
        for (view in listOf(outgoing.root, incoming.root, incoming.photo)) view.animate().cancel()
        reset(incoming.root)
        reset(incoming.photo)
        incoming.root.translationZ = 1f
        outgoing.root.translationZ = 0f
        incoming.root.visibility = View.VISIBLE

        val hideOutgoing = Runnable {
            outgoing.root.visibility = View.INVISIBLE
            outgoing.photo.animate().cancel()
            reset(outgoing.root)
            reset(outgoing.photo)
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
        motion = null
        when {
            type == "kenburns" -> kenBurns(incoming.photo, duration)
            settings.slowZoom -> slowZoom(incoming.photo, duration)
        }
    }

    private fun photoMillis(transitionMs: Long) = settings.intervalSeconds * 1000L + transitionMs * 2

    /** Zooms in by a few percent at a steady, barely noticeable pace while the photo is on screen. */
    private fun slowZoom(view: View, transitionMs: Long) {
        val total = photoMillis(transitionMs)
        val scale = 1f + (total / 1000f * 0.005f).coerceIn(0.03f, 0.08f)
        startMotion(view, Motion(scale, 0f, 0f), total)
    }

    private fun startMotion(view: View, target: Motion, durationMs: Long) {
        motion = target
        if (paused) return
        view.animate()
            .scaleX(target.scale).scaleY(target.scale)
            .translationX(target.dx).translationY(target.dy)
            .setDuration(durationMs)
            .setInterpolator(LinearInterpolator())
    }

    /** Slow zoom with a gentle drift; the drift never exceeds the zoom margin, so no edges show. */
    private fun kenBurns(view: View, transitionMs: Long) {
        val total = photoMillis(transitionMs)
        val maxScale = 1.15f
        val dx = (Random.nextFloat() * 2 - 1) * 0.06f * view.width
        val dy = (Random.nextFloat() * 2 - 1) * 0.06f * view.height
        val zoomIn = Random.nextBoolean()
        view.scaleX = if (zoomIn) 1f else maxScale
        view.scaleY = view.scaleX
        view.translationX = if (zoomIn) 0f else dx
        view.translationY = if (zoomIn) 0f else dy
        val target = if (zoomIn) Motion(maxScale, dx, dy) else Motion(1f, 0f, 0f)
        startMotion(view, target, total)
    }

    private fun reset(view: View) {
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }

    // ---- Overlays ----

    private fun applyOverlays() {
        handler.removeCallbacks(tickClock)
        clock.visibility = if (settings.showClock) View.VISIBLE else View.GONE
        if (settings.showClock) tickClock.run()
        photoDate.visibility = if (settings.showDate) View.VISIBLE else View.GONE
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
    }
}
