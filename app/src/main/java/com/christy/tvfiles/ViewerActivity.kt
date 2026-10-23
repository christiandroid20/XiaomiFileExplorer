package com.christy.tvfiles

import android.graphics.BitmapFactory
import android.graphics.Color
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.view.View
import android.view.KeyEvent
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
    private var videoTitleView: TextView? = null
    private var fallbackShown = false

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

        val isVideo = type == "video"
        if (isVideo) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }

        val root: View = if (isVideo) FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
        } else baseLayout(file.name)

        val playerSurface = PlayerView(this).apply {
            useController = true
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.BLACK)
        }
        playerView = playerSurface
        val videoTitle = if (isVideo) TextView(this).apply {
            text = file.name
            setTextColor(Color.WHITE)
            textSize = 18f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(10), dp(24), dp(10))
            setBackgroundColor(Color.rgb(18, 20, 26))
            elevation = dp(8).toFloat()
        } else null
        videoTitleView = videoTitle
        if (isVideo) {
            (root as FrameLayout).addView(playerSurface, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
            (root as FrameLayout).addView(videoTitle, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP
            ))
        } else {
            (root as LinearLayout).addView(playerSurface, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
        }

        val status = TextView(this).apply {
            setTextColor(Color.rgb(255, 190, 110))
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        statusView = status
        if (isVideo) {
            (root as FrameLayout).addView(status, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ))
        } else {
            (root as LinearLayout).addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(root)
        installFocusHighlight(playerSurface)
        if (isVideo) {
            playerSurface.setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    videoTitleView?.visibility = visibility
                }
            )
        }

        try {
            val exo = ExoPlayer.Builder(this).build()
            player = exo
            playerSurface.player = exo

            exo.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    status.text = "Este formato o códec no se pudo reproducir."
                    status.visibility = View.VISIBLE
                    offerExternalPlayer(file, "No se pudo reproducir con el visor integrado. ¿Quieres elegir otra aplicación?")
                }
            })

            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .build()
            exo.setMediaItem(item)
            exo.prepare()
            exo.playWhenReady = true
            playerSurface.requestFocus()
        } catch (t: Throwable) {
            status.text = "No se pudo iniciar el reproductor."
            status.visibility = View.VISIBLE
            offerExternalPlayer(file, "No se pudo iniciar el visor integrado. ¿Quieres elegir otra aplicación?")
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val surface = playerView
        if (event.action == KeyEvent.ACTION_DOWN && surface != null &&
            intent.getStringExtra("fileType") == "video" && !surface.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    surface.showController()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun installFocusHighlight(view: View) {
        val focusables = ArrayList<View>()
        fun collect(v: View) {
            if (v.isFocusable) focusables.add(v)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) collect(v.getChildAt(i))
            }
        }
        collect(view)
        focusables.forEach { control ->
            control.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.10f else 1f)
                    .scaleY(if (focused) 1.10f else 1f).setDuration(100).start()
                if (focused) {
                    v.background = GradientDrawable().apply {
                        setColor(Color.rgb(54, 78, 112))
                        setStroke(dp(2), Color.WHITE)
                        cornerRadius = dp(8).toFloat()
                    }
                } else {
                    v.background = null
                }
            }
        }
    }

    private fun offerExternalPlayer(file: File, message: String) {
        if (isFinishing || fallbackShown) return
        fallbackShown = true
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            AlertDialog.Builder(this)
                .setTitle("Abrir con otra aplicación")
                .setMessage(message)
                .setNegativeButton("Volver") { dialog, _ ->
                    dialog.dismiss()
                    fallbackShown = false
                }
                .setPositiveButton("Elegir aplicación") { dialog, _ ->
                    dialog.dismiss()
                    openExternal(file)
                }
                .setOnCancelListener { fallbackShown = false }
                .show()
        }
    }

    private fun openExternal(file: File) {
        try {
            player?.pause()
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: when (typeFromExtension(file.extension.lowercase())) {
                    "image" -> "image/*"
                    "audio" -> "audio/*"
                    "video" -> "video/*"
                    else -> "*/*"
                }
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Abrir con"))
            finish()
        } catch (t: Throwable) {
            Toast.makeText(this, "No hay otra aplicación compatible instalada", Toast.LENGTH_LONG).show()
            fallbackShown = false
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