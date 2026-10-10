package app.photoframe.tv

data class Photo(
    val id: String,
    /** Base lh3.googleusercontent.com URL, without any "=w…-h…" size suffix. */
    val url: String,
    val width: Int,
    val height: Int,
    /** When the photo was taken, epoch millis (0 if unknown). */
    val takenAt: Long,
    /** A video; [url] then serves its poster frame as an image, and [videoUrl] the video itself. */
    val isVideo: Boolean = false,
) {
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1.5f

    /** Google resizes on the server: fit inside w×h, or with crop, fill w×h exactly. */
    fun sizedUrl(w: Int, h: Int, crop: Boolean = false) = "$url=w$w-h$h" + if (crop) "-c" else ""

    /** An MP4 of the video, transcoded by Google. */
    fun videoUrl() = "$url=$VIDEO_SUFFIX"

    companion object {
        const val VIDEO_SUFFIX = "dv"
    }
}
