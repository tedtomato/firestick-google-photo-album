package app.photoframe.tv

import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

class SettingsActivity : ComponentActivity() {

    private class Row(val view: View, val value: TextView)

    private lateinit var library: Library
    private val settings get() = library.settings

    private lateinit var rows: LinearLayout
    private lateinit var albumList: LinearLayout
    private lateinit var addAlbumRow: View
    private lateinit var qrImage: ImageView
    private lateinit var qrHint: TextView
    private val server by lazy { ConfigServer(library) }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Library.KEY_VERSION) renderAlbums()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        library = Library.get(this)
        rows = findViewById(R.id.rows)
        qrImage = findViewById(R.id.qr_image)
        qrHint = findViewById(R.id.qr_hint)
        buildRows()
    }

    override fun onStart() {
        super.onStart()
        library.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        server.start()
        showQrCode()
        renderAlbums()
    }

    override fun onStop() {
        library.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        server.stop()
        super.onStop()
    }

    private fun buildRows() {
        rows.addView(text(getString(R.string.app_name), 34f, bold = true).apply { setPadding(0, 0, 0, dp(12)) })
        val start = row("▶   Start slideshow") { finish() }
        rows.addView(start.view)

        rows.addView(section("Albums"))
        albumList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rows.addView(albumList)
        addAlbumRow = row("+   Add album link…") { promptAddAlbum() }.view
        rows.addView(addAlbumRow)
        rows.addView(row("⟳   Check albums for new photos now") {
            library.refreshAsync()
            toast("Checking albums…")
        }.view)

        rows.addView(section("Slideshow"))
        rows.addView(choiceRow(
            "Change photo every",
            Settings.INTERVALS.map { it.toString() }, Settings.INTERVALS.map { Settings.intervalLabel(it) },
            { settings.intervalSeconds.toString() },
        ) { settings.intervalSeconds = it.toInt() })
        rows.addView(choiceRow(
            "Transition",
            Settings.TRANSITIONS.keys.toList(), Settings.TRANSITIONS.values.toList(),
            { settings.transition },
        ) { settings.transition = it })
        rows.addView(choiceRow("Slow zoom on each photo", ON_OFF, ON_OFF_LABELS, { onOff(settings.slowZoom) }) {
            settings.slowZoom = it == "on"
        })
        rows.addView(choiceRow(
            "Photo display",
            Settings.FIT_MODES.keys.toList(), Settings.FIT_MODES.values.toList(),
            { settings.fitMode },
        ) { settings.fitMode = it })
        rows.addView(choiceRow("Two portrait photos side by side", ON_OFF, ON_OFF_LABELS, { onOff(settings.pairPortraits) }) {
            settings.pairPortraits = it == "on"
        })
        rows.addView(choiceRow(
            "Order",
            listOf("random", "album"), listOf("Random", "Album order"),
            { if (settings.shuffle) "random" else "album" },
        ) { settings.shuffle = it == "random" })
        rows.addView(choiceRow("Show clock", ON_OFF, ON_OFF_LABELS, { onOff(settings.showClock) }) {
            settings.showClock = it == "on"
        })
        rows.addView(choiceRow("Show date photo was taken", ON_OFF, ON_OFF_LABELS, { onOff(settings.showDate) }) {
            settings.showDate = it == "on"
        })

        rows.addView(section("Updates"))
        rows.addView(choiceRow(
            "Check albums for new photos",
            Settings.REFRESH_MINUTES.map { it.toString() }, Settings.REFRESH_MINUTES.map { Settings.refreshLabel(it) },
            { settings.refreshMinutes.toString() },
        ) { settings.refreshMinutes = it.toInt() })

        rows.addView(text("During the slideshow:  ◀ ▶ previous / next    OK pause    ☰ or ▼ this screen", 16f).apply {
            alpha = 0.6f
            setPadding(0, dp(28), 0, dp(8))
        })
        start.view.requestFocus()
    }

    private fun renderAlbums() {
        val focusedIndex = albumList.indexOfChild(currentFocus)
        albumList.removeAllViews()
        val albums = library.albums()
        if (albums.isEmpty()) {
            albumList.addView(text("No albums yet. Add a shared album link below, or scan the QR code with your phone.", 18f).apply {
                alpha = 0.7f
                setPadding(dp(4), dp(8), 0, dp(8))
            })
        }
        for (album in albums) {
            albumList.addView(row(album.title ?: album.link, library.statusOf(album)) { showAlbum(album) }.view)
        }
        if (focusedIndex >= 0) {
            val next = albumList.getChildAt(minOf(focusedIndex, albumList.childCount - 1))
            (next?.takeIf { it.isFocusable } ?: addAlbumRow).requestFocus()
        }
    }

    private fun showAlbum(album: Album) {
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(album.title ?: "Album")
            .setMessage("${library.statusOf(album)}\n\n${album.link}")
            .setPositiveButton("Refresh") { _, _ -> library.refreshAsync(album.link) }
            .setNegativeButton("Remove") { _, _ ->
                library.removeAlbum(album.link)
                toast("Album removed")
            }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun promptAddAlbum() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://photos.app.goo.gl/…"
            setSingleLine()
        }
        val wrapper = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle("Add a shared album")
            .setMessage(
                "In Google Photos, open the album and choose Share → Create link, then type that link here.\n\n" +
                    "Easier: scan the QR code on this screen with your phone and paste the link there."
            )
            .setView(wrapper)
            .setPositiveButton("Add") { _, _ -> toast(library.messageFor(library.addAlbum(input.text.toString()))) }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun showQrCode() {
        val ip = Net.localIpv4()
        val port = server.port
        if (ip == null || port == null) {
            qrImage.visibility = View.GONE
            qrHint.text = "Connect the Fire TV to your home network to add albums from your phone."
            return
        }
        val url = "http://$ip:$port/"
        qrImage.setImageBitmap(QrCode.render(url, dp(240)))
        qrImage.visibility = View.VISIBLE
        qrHint.text = "Scan with your phone (on the same Wi-Fi) to add or remove albums, or open\n$url"
    }

    // ---- Small view helpers ----

    private fun choiceRow(
        label: String,
        keys: List<String>,
        labels: List<String>,
        current: () -> String,
        onPick: (String) -> Unit,
    ): View {
        lateinit var created: Row
        created = row(label, labels.getOrElse(keys.indexOf(current())) { "" }) {
            AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(label)
                .setSingleChoiceItems(labels.toTypedArray(), keys.indexOf(current())) { dialog, which ->
                    onPick(keys[which])
                    created.value.text = labels[which]
                    dialog.dismiss()
                }
                .show()
        }
        return created.view
    }

    private fun row(label: String, value: String? = null, onClick: () -> Unit): Row {
        val labelView = text(label, 21f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 2
        }
        val valueView = text(value.orEmpty(), 19f).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.END
            alpha = 0.75f
            maxLines = 3
            visibility = if (value == null) View.GONE else View.VISIBLE
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.row_background)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
            addView(labelView)
            addView(valueView)
            setOnClickListener { onClick() }
        }
        return Row(container, valueView)
    }

    private fun section(title: String) = text(title.uppercase(), 15f, bold = true).apply {
        alpha = 0.6f
        letterSpacing = 0.1f
        setPadding(dp(4), dp(28), 0, dp(4))
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(0xFFFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private companion object {
        const val DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert
        val ON_OFF = listOf("on", "off")
        val ON_OFF_LABELS = listOf("On", "Off")
        fun onOff(value: Boolean) = if (value) "on" else "off"
    }
}
