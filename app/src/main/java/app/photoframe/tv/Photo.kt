package app.photoframe.tv

data class Photo(
    val id: String,
    /** Base lh3.googleusercontent.com URL, without any "=w…-h…" size suffix. */
    val url: String,
    val width: Int,
    val height: Int,
    /** When the photo was taken, epoch millis (0 if unknown). */
    val takenAt: Long,
    /** A video; [url] then serves its poster frame as an image, and [videoUrls] the video itself. */
    val isVideo: Boolean = false,
) {
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1.5f

    /** Google resizes on the server: fit inside w×h, or with crop, fill w×h exactly. */
    fun sizedUrl(w: Int, h: Int, crop: Boolean = false) = "$url=w$w-h$h" + if (crop) "-c" else ""

    /**
     * MP4s of the video, best first: Google's H.264 versions at 1080p, 720p and 360p (each only exists if the
     * video is at least that big), then the original file, whose codec an older Fire TV may not play.
     */
    fun videoUrls() = VIDEO_FORMATS.map { "$url=$it" }

    private companion object {
        val VIDEO_FORMATS = listOf("m37", "m22", "m18", "dv")
    }
}
