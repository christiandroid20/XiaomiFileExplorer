package com.christy.tvfiles

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Visor multimedia interno: imágenes con ImageView y audio/video con Media3.
 * Mantiene la reproducción dentro de la app y ofrece controles compatibles con D-pad.
 */
class ViewerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var statusView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra("filePath")
        val requestedType = intent.getStringExtra("fileType")
        if (path.isNullOrBlank()) {
            finish()
            return
        }

        val file = File(path)
        if (!file.isFile || !file.canRead()) {
            showMessage("No se puede leer este archivo")
            return
        }

        val type = requestedType ?: typeFromExtension(file.extension.lowercase())
        when (type) {
            "image" -> showImage(file)
            "audio", "video" -> showPlayer(file, type)
            else -> showMessage("Formato multimedia no compatible")
        }
    }

    private fun typeFromExtension(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg", "png", "gif", "bmp", "webp" -> "image"
        "mp3", "wav", "flac", "ogg", "m4a", "aac", "wma", "opus" -> "audio"
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v", "mpg", "mpeg", "3gp" -> "video"
        else -> "unknown"
    }

    private fun baseLayout(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 16, 23))
            isFocusableInTouchMode = true

            val heading = TextView(this@ViewerActivity).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 20f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(12), dp(24), dp(12))
                setBackgroundColor(Color.rgb(25, 30, 41))
            }
            addView(heading, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ))
        }
    }

    private fun showImage(file: File) {
        val root = baseLayout(file.name)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
            contentDescription = file.name
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            showMessage("No se pudo cargar esta imagen")
            return
        }

        val maxDimension = 2560
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDimension ||
            bounds.outHeight / (sample * 2) >= maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
        if (bitmap == null) {
            showMessage("No se pudo cargar esta imagen")
            return
        }
        image.setImageBitmap(bitmap)

        root.addView(image, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val hint = TextView(this).apply {
            text = "Atrás: volver al explorador"
            setTextColor(Color.LTGRAY)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        root.addView(hint)
        setContentView(root)
    }

    private fun showPlayer(file: File, type: String) {
        if (type == "video") {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val root = baseLayout(file.name)
        val playerSurface = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        playerView = playerSurface
        root.addView(playerSurface, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val status = TextView(this).apply {
            setTextColor(Color.rgb(255, 190, 110))
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        statusView = status
        root.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)

        try {
            val exo = ExoPlayer.Builder(this).build()
            player = exo
            playerSurface.player = exo

            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    status.text = "No se pudo reproducir este formato o códec. Usa Atrás para volver."
                    status.visibility = View.VISIBLE
                }
            })

            val metadata = MediaMetadata.Builder().setTitle(file.name).build()
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(metadata)
                .build()
            exo.setMediaItem(item)
            exo.prepare()
            exo.playWhenReady = true
            playerSurface.requestFocus()
        } catch (t: Throwable) {
            status.text = "No se pudo iniciar el reproductor. Usa Atrás para volver."
            status.visibility = View.VISIBLE
        }
    }

    private fun showMessage(message: String) {
        val root = baseLayout("Visor multimedia")
        val text = TextView(this).apply {
            this.text = message
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(text, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        playerView?.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}