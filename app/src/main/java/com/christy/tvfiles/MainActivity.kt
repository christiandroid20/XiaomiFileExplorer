package com.christy.tvfiles

import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var pathLabel: TextView
    private lateinit var countLabel: TextView
    private var currentPath = File("/storage/emulated/0")
    private var currentFilter = FileType.ALL

    enum class FileType { ALL, VIDEO, AUDIO, IMAGE, APK, DOCUMENT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.list)
        pathLabel = findViewById(R.id.t1)
        countLabel = findViewById(R.id.t2)
        recyclerView.layoutManager = GridLayoutManager(this, 4)
        recyclerView.setHasFixedSize(false)

        createNavigationButtons()
        checkStorageAccess()
    }

    /**
     * Android 11+ treats access to all files as a special app setting.
     * Android does not allow an app to silently grant this access during install;
     * the user must confirm it in system settings.
     */
    private fun checkStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                loadDirectory(currentPath, currentFilter)
            } else {
                Toast.makeText(
                    this,
                    "Activa \"Permitir administrar todos los archivos\" para usar el explorador",
                    Toast.LENGTH_LONG
                ).show()
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {
                        Toast.makeText(
                            this,
                            "Abre Ajustes > Aplicaciones > XiaomiFileExplorer > Acceso a todos los archivos",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "Concede el acceso a todos los archivos desde los ajustes de la aplicación",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                100
            )
        } else {
            loadDirectory(currentPath, currentFilter)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            loadDirectory(currentPath, currentFilter)
        }
    }

    private fun createNavigationButtons() {
        val bar = findViewById<LinearLayout>(R.id.bar)
        val entries = listOf(
            "Todos" to FileType.ALL,
            "Videos" to FileType.VIDEO,
            "Música" to FileType.AUDIO,
            "Fotos" to FileType.IMAGE,
            "APK" to FileType.APK,
            "Documentos" to FileType.DOCUMENT
        )
        entries.forEach { (label, type) ->
            val button = Button(this).apply {
                text = label
                isFocusable = true
                isAllCaps = false
                setOnClickListener {
                    currentFilter = type
                    loadDirectory(currentPath, currentFilter)
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 3
                    marginEnd = 3
                }
            }
            bar.addView(button)
        }
        val drivesButton = Button(this).apply {
            text = "Unidades"
            isFocusable = true
            isAllCaps = false
            setOnClickListener {
                currentFilter = FileType.ALL
                loadDirectory(File("/storage"), currentFilter)
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(drivesButton)
    }

    private fun loadDirectory(dir: File, filter: FileType) {
        currentPath = dir
        pathLabel.text = dir.name.ifBlank { dir.absolutePath }
        countLabel.text = dir.absolutePath
        recyclerView.adapter = FileAdapter(emptyList())

        Thread {
            val files = try {
                dir.listFiles()?.filter { file ->
                    !file.isHidden || file.name.startsWith(".")
                }?.filter { file ->
                    file.isDirectory || when (filter) {
                        FileType.ALL -> true
                        FileType.VIDEO -> isVideo(file.name)
                        FileType.AUDIO -> isAudio(file.name)
                        FileType.IMAGE -> isImage(file.name)
                        FileType.APK -> file.extension.equals("apk", true)
                        FileType.DOCUMENT -> isDocument(file.extension.lowercase())
                    }
                }?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
            } catch (_: SecurityException) {
                emptyList()
            }

            runOnUiThread {
                recyclerView.adapter = FileAdapter(files)
                countLabel.text = "${dir.absolutePath}  •  ${files.size} elementos"
                if (files.isEmpty()) {
                    Toast.makeText(this, "No hay archivos visibles o falta permiso de almacenamiento", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private inner class FileAdapter(private val files: List<File>) : RecyclerView.Adapter<FileHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileHolder {
            val view = layoutInflater.inflate(R.layout.item_card, parent, false)
            return FileHolder(view)
        }

        override fun getItemCount() = files.size

        override fun onBindViewHolder(holder: FileHolder, position: Int) {
            val file = files[position]
            holder.name.text = file.name
            holder.sub.text = if (file.isDirectory) "Carpeta" else readableSize(file.length())
            bindFileIcon(file, holder.icon)
            holder.itemView.contentDescription = file.name
            holder.itemView.setOnClickListener {
                if (file.isDirectory) {
                    loadDirectory(file, currentFilter)
                } else {
                    openFile(file)
                }
            }
            holder.itemView.setOnLongClickListener {
                Toast.makeText(this@MainActivity, file.absolutePath, Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private class FileHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.name)
        val sub: TextView = view.findViewById(R.id.sub)
    }

    private fun bindFileIcon(file: File, imageView: ImageView) {
        if (file.isDirectory) {
            imageView.setImageResource(R.drawable.ic_folder)
            return
        }
        val extension = file.extension.lowercase()
        if (extension == "apk") {
            val icon = getApkIcon(file.absolutePath)
            if (icon != null) imageView.setImageDrawable(icon)
            else imageView.setImageResource(R.drawable.ic_apk)
            return
        }
        imageView.setImageResource(when (extension) {
            "mp4", "mkv", "avi", "mov", "webm" -> R.drawable.ic_video
            "mp3", "flac", "wav", "aac", "ogg" -> R.drawable.ic_music
            "jpg", "jpeg", "png", "webp", "gif" -> R.drawable.ic_image
            "pdf", "docx", "doc", "xlsx", "xls", "pptx", "ppt", "txt" -> R.drawable.ic_doc
            "py", "xml", "json", "html", "js", "css", "md", "cpp", "c" -> R.drawable.ic_code
            "zip", "rar", "7z", "tar", "gz" -> R.drawable.ic_archive
            else -> R.drawable.ic_file
        })
    }

    private fun getApkIcon(path: String): Drawable? = try {
        val pm = packageManager
        val info = pm.getPackageArchiveInfo(path, 0)
        info?.applicationInfo?.let {
            it.sourceDir = path
            it.publicSourceDir = path
            it.loadIcon(pm)
        }
    } catch (_: Exception) { null }

    private fun openFile(file: File) {
        if (isVideo(file.name)) {
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("filePath", file.absolutePath))
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, contentType(file.extension))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Abrir con"))
        } catch (_: Exception) {
            Toast.makeText(this, "No hay una aplicación compatible para abrir este archivo", Toast.LENGTH_LONG).show()
        }
    }

    private fun contentType(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif" -> "image/*"
        "mp3", "flac", "wav", "aac", "ogg" -> "audio/*"
        "pdf" -> "application/pdf"
        "apk" -> "application/vnd.android.package-archive"
        "txt", "log", "md" -> "text/plain"
        else -> "*/*"
    }

    private fun readableSize(size: Long): String {
        if (size < 1024) return "$size B"
        if (size < 1024 * 1024) return "${size / 1024} KB"
        return "${size / (1024 * 1024)} MB"
    }

    private fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "avi", "mov", "webm")
    private fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("mp3", "flac", "wav", "aac", "ogg")
    private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif")
    private fun isDocument(ext: String) = ext in setOf("docx", "doc", "pdf", "xlsx", "xls", "pptx", "ppt", "txt", "xml", "json", "py", "html", "md")

    @Deprecated("Deprecated in Android API, retained for older TV devices")
    override fun onBackPressed() {
        val parent = currentPath.parentFile
        if (parent != null && currentPath.absolutePath != "/storage/emulated/0") {
            loadDirectory(parent, currentFilter)
        } else if (currentPath.absolutePath != "/storage/emulated/0") {
            loadDirectory(File("/storage/emulated/0"), currentFilter)
        } else {
            super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadDirectory(currentPath, currentFilter)
            } else {
                Toast.makeText(this, "Sin permiso de almacenamiento no se pueden listar los archivos", Toast.LENGTH_LONG).show()
            }
        }
    }
}
