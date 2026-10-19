package com.christy.tvfiles

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private var currentPath: File = File("/storage/emulated/0")
    private var currentFilter: FileType = FileType.ALL
    
    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewRunnable: Runnable? = null
    private var mediaPreviewPlayer: MediaPlayer? = null

    enum class FileType {
        ALL, VIDEO, AUDIO, IMAGE, APK, DOCUMENT
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = GridLayoutManager(this, 4)

        loadDirectory(currentPath, currentFilter)
    }

    // Filtrado eficiente dentro de la unidad seleccionada (evita congelamientos en la TV)
    private fun loadDirectory(dir: File, filter: FileType) {
        currentPath = dir
        Thread {
            val files = dir.listFiles()?.filter { file ->
                if (file.isHidden && !file.name.startsWith(".")) return@filter false
                when (filter) {
                    FileType.ALL -> true
                    FileType.VIDEO -> file.isDirectory || isVideo(file.name)
                    FileType.AUDIO -> file.isDirectory || isAudio(file.name)
                    FileType.IMAGE -> file.isDirectory || isImage(file.name)
                    FileType.APK -> file.isDirectory || file.extension.lowercase() == "apk"
                    FileType.DOCUMENT -> file.isDirectory || isDocument(file.extension.lowercase())
                }
            }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()

            runOnUiThread {
                // TODO: Conecta aquí tu adapter pasando la lista 'files'
            }
        }.start()
    }

    // Selector inteligente de iconos: Icono real para APKs, carpetas y genéricos por extensión para lo demás
    fun bindFileIcon(context: Context, file: File, imageView: ImageView) {
        if (file.isDirectory) {
            imageView.setImageResource(R.drawable.ic_folder)
            return
        }

        val extension = file.name.substringAfterLast('.', "").lowercase()

        if (extension == "apk") {
            // Extrae el logotipo oficial de la aplicación sin necesidad de instalarla
            val realApkIcon = getApkIcon(context, file.absolutePath)
            if (realApkIcon != null) {
                imageView.setImageDrawable(realApkIcon)
            } else {
                imageView.setImageResource(R.drawable.ic_apk)
            }
        } else {
            val genericRes = when (extension) {
                "mp4", "mkv", "avi", "mov", "webm" -> R.drawable.ic_video
                "mp3", "flac", "wav", "aac", "ogg" -> R.drawable.ic_music
                "jpg", "jpeg", "png", "webp", "gif" -> R.drawable.ic_image
                "pdf", "docx", "doc", "xlsx", "xls", "pptx", "ppt", "txt" -> R.drawable.ic_doc
                "py", "xml", "json", "html", "js", "css", "md", "cpp", "c" -> R.drawable.ic_code
                "zip", "rar", "7z", "tar", "gz" -> R.drawable.ic_archive
                else -> R.drawable.ic_file
            }
            imageView.setImageResource(genericRes)
        }
    }

    // Extractor seguro del icono desde el archivo APK
    private fun getApkIcon(context: Context, apkPath: String): Drawable? {
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(apkPath, 0)
            packageInfo?.applicationInfo?.let { appInfo ->
                appInfo.sourceDir = apkPath
                appInfo.publicSourceDir = apkPath
                appInfo.loadIcon(pm)
            }
        } catch (e: Exception) {
            null
        }
    }

    // Abrir reproductor interno con control de vistos para series, animes o cursos
    private fun openVideoPlayer(file: File) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("filePath", file.absolutePath)
        }
        startActivity(intent)
    }

    private fun isVideo(name: String) = name.endsWith(".mp4", true) || name.endsWith(".mkv", true) || name.endsWith(".avi", true)
    private fun isAudio(name: String) = name.endsWith(".mp3", true) || name.endsWith(".flac", true) || name.endsWith(".wav", true)
    private fun isImage(name: String) = name.endsWith(".jpg", true) || name.endsWith(".png", true) || name.endsWith(".jpeg", true)
    private fun isDocument(ext: String) = ext in listOf("docx", "doc", "pdf", "xlsx", "txt", "xml", "json", "py", "html")

    override fun onBackPressed() {
        if (currentPath.parentFile != null && currentPath.absolutePath != "/") {
            currentPath.parentFile?.let { loadDirectory(it, currentFilter) }
        } else {
            super.onBackPressed()
        }
    }
	// Para encender el servidor FTP local y pasar archivos por red
private fun startFtpServer() {
    val intent = Intent(this, FtpServerService::class.java)
    startService(intent)
}

// Para apagarlo
private fun stopFtpServer() {
    val intent = Intent(this, FtpServerService::class.java)
    stopService(intent)
}
}