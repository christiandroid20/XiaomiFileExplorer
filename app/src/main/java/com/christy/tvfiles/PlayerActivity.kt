package com.christy.tvfiles

import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

class PlayerActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private var filePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        videoView = VideoView(this)
        setContentView(videoView)

        filePath = intent.getStringExtra("filePath")
        if (filePath == null) {
            finish()
            return
        }

        val uri = Uri.parse(filePath)
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)

        videoView.setMediaController(mediaController)
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { mp -> mp.start() }

        // Marca automáticamente el archivo como visto al terminar
        videoView.setOnCompletionListener {
            filePath?.let { path ->
                WatchHistoryManager.markAsWatched(this, path, true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        videoView.stopPlayback()
    }
}