package com.christy.tvfiles

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.Settings
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var pathLabel: TextView
    private lateinit var countLabel: TextView
    private var currentPath = File("/storage/emulated/0")
    private var currentFilter = FileType.ALL
    private var currentDocumentDir: DocumentFile? = null
    private var savedExternalRoot: DocumentFile? = null
    private val savedExternalRoots = mutableListOf<DocumentFile>()

    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { handleTreeUri(it) }
    }

    // Used when we launch a StorageVolume.createAccessIntent(...) which is an arbitrary intent
    private val volumePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (uri != null) {
            handleTreeUri(uri)
        } else {
            // Some devices return the uri inside extras or have no data; fall back to OpenDocumentTree
            // Nothing to do here - user can try again.
            Log.w("MainActivity", "volumePicker returned no uri")
        }
    }

    enum class FileType { ALL, VIDEO, AUDIO, IMAGE, APK, DOCUMENT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.list)
        pathLabel = findViewById(R.id.t1)
        countLabel = findViewById(R.id.t2)
        recyclerView.layoutManager = GridLayoutManager(this, 4)
        recyclerView.setHasFixedSize(false)

        restoreExternalTree()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager() && currentDocumentDir == null) {
            loadDirectory(currentPath, currentFilter)
        }
    }

    private fun restoreExternalTree() {
        val prefs = getSharedPreferences("storage_access", MODE_PRIVATE)
        val values = prefs.getStringSet("external_tree_uris", emptySet()).orEmpty()
        values.forEach { value ->
            try {
                val uri = Uri.parse(value)
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                DocumentFile.fromTreeUri(this, uri)?.let { savedExternalRoots.add(it) }
            } catch (_: Exception) { }
        }
        if (savedExternalRoots.isEmpty()) {
            val legacy = prefs.getString("external_tree_uri", null)
            if (legacy != null) try {
                val uri = Uri.parse(legacy)
                DocumentFile.fromTreeUri(this, uri)?.let {
                    savedExternalRoots.add(it)
                    prefs.edit().putStringSet("external_tree_uris", setOf(legacy)).apply()
                }
            } catch (_: Exception) { }
        }
        savedExternalRoot = savedExternalRoots.lastOrNull()
    }

    private fun showStorageChoices() {
        val choices = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        // Internal storage
        choices.add("Memoria interna")
        actions.add { currentDocumentDir = null; loadDirectory(File("/storage/emulated/0"), currentFilter) }

        // Detect storage volumes (microSD, USB-OTG, NVMe, etc.) using StorageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val volumes = try { sm.storageVolumes } catch (_: Exception) { emptyList<StorageVolume>() }
            volumes?.forEach { vol ->
                try {
                    // Skip the primary internal volume because we already added it
                    val isPrimary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        // StorageVolume doesn't expose isPrimary() in older APIs; approximate by isEmulated
                        vol.isEmulated
                    } else true
                    if (isPrimary) return@forEach

                    val label = try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.getDescription(this) else "Unidad externa" } catch (_: Exception) { "Unidad externa" }

                    choices.add(label)
                    actions.add {
                        // Try to request access to the whole volume using createAccessIntent (Android N+)
                        try {
                            val intent = vol.createAccessIntent(null)
                            if (intent != null) {
                                // Launch the vendor/system intent which usually opens the Documents UI preselected on this volume
                                volumePicker.launch(intent)
                            } else {
                                // Fallback to generic tree picker
                                treePicker.launch(null)
                            }
                        } catch (ex: Exception) {
                            Log.w("MainActivity", "createAccessIntent failed", ex)
                            treePicker.launch(null)
                        }
                    }
                } catch (_: Exception) { }
            }
        }

        // Option to add a storage via generic picker
        choices.add("Añadir microSD / USB-C…")
        actions.add { treePicker.launch(null) }

        // Previously saved external roots
        savedExternalRoots.forEach { root ->
            choices.add("Abrir: " + (root.name ?: "Unidad externa"))
            actions.add { savedExternalRoot = root; loadDocumentDirectory(root, currentFilter) }
        }

        android.app.AlertDialog.Builder(this).setTitle("Seleccionar almacenamiento")
            .setItems(choices.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun handleTreeUri(uri: Uri) {
        if (uri == null) return
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (ex: SecurityException) {
                // Some providers don't grant persistable permissions; continue but warn the user
                Log.w("MainActivity", "No se pudo tomar permiso persistente", ex)
                runOnUiThread { Toast.makeText(this, "No se pudo conservar el permiso; puede que tenga que volver a autorizarlo", Toast.LENGTH_LONG).show() }
            }
            val root = DocumentFile.fromTreeUri(this, uri)
            if (root != null) {
                // Quick check: try listing to detect whether the system granted access to the actual root
                var canList = true
                try {
                    // Attempt to list to detect blocked roots; this can still be empty for a legitimately empty folder
                    root.listFiles()
                } catch (ex: Exception) {
                    canList = false
                }

                if (!canList) {
                    // Android can prevent selecting the real root of some removable volumes.
                    // Explain and offer to pick a subfolder explicitly.
                    AlertDialog.Builder(this)
                        .setTitle("Acceso limitado")
                        .setMessage("Android impidió acceder a la raíz de esa unidad. Selecciona una carpeta dentro de la tarjeta o unidad para conceder acceso a esa carpeta.")
                        .setPositiveButton("Seleccionar carpeta") { _, _ ->
                            // Launch a generic tree picker so the user can pick a subfolder. Passing the same uri as initial is not guaranteed to work across devices; we let the system decide.
                            treePicker.launch(uri)
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                    return
                }

                savedExternalRoot = root
                if (savedExternalRoots.none { it.uri == root.uri }) savedExternalRoots.add(root)
                val savedUris = savedExternalRoots.map { it.uri.toString() }.toSet()
                getSharedPreferences("storage_access", MODE_PRIVATE).edit().putStringSet("external_tree_uris", savedUris).apply()
                loadDocumentDirectory(root, currentFilter)
            }
        } catch (ex: Exception) {
            Log.w("MainActivity", "handleTreeUri failed", ex)
            runOnUiThread { Toast.makeText(this, "Android no permitió conservar el acceso a esa unidad", Toast.LENGTH_LONG).show() }
        }
    }

    private fun loadDocumentDirectory(dir: DocumentFile, filter: FileType) {
        currentDocumentDir = dir
        pathLabel.text = dir.name ?: "Almacenamiento externo"
        countLabel.text = dir.uri.toString()
        recyclerView.adapter = DocumentAdapter(emptyList())
        Thread {
            val documents = try {
                dir.listFiles().filter { it.name != null }.filter { it.isDirectory || when (filter) {
                    FileType.ALL -> true
                    FileType.VIDEO -> isVideo(it.name ?: "")
                    FileType.AUDIO -> isAudio(it.name ?: "")
                    FileType.IMAGE -> isImage(it.name ?: "")
                    FileType.APK -> (it.name ?: "").substringAfterLast('.', "").equals("apk", true)
                    FileType.DOCUMENT -> isDocument((it.name ?: "").substringAfterLast('.', "").lowercase())
                }}.sortedWith(compareBy<DocumentFile>({ !it.isDirectory }, { (it.name ?: "").lowercase() }))
            } catch (ex: Exception) {
                Log.w("MainActivity", "Error listing document directory", ex)
                emptyList()
            }
            runOnUiThread {
                recyclerView.adapter = DocumentAdapter(documents)
                countLabel.text = (dir.name ?: "Unidad externa") + " • " + documents.size + " elementos"
                if (documents.isEmpty()) Toast.makeText(this, "Carpeta vacía o Android no permite leer su contenido", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private inner class DocumentAdapter(private val documents: List<DocumentFile>) : RecyclerView.Adapter<FileHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileHolder =
            FileHolder(layoutInflater.inflate(R.layout.item_card, parent, false))
        override fun getItemCount() = documents.size
        override fun onBindViewHolder(holder: FileHolder, position: Int) {
            val doc = documents[position]
            val name = doc.name ?: "Archivo"
            holder.name.text = name
            holder.sub.text = if (doc.isDirectory) "Carpeta" else readableSize(doc.length())
            bindDocumentIcon(name, doc.isDirectory, holder.icon)
            holder.itemView.contentDescription = name
            holder.itemView.setOnClickListener {
                if (doc.isDirectory) loadDocumentDirectory(doc, currentFilter) else {
                    try {
                        val mime = contentResolver.getType(doc.uri) ?: contentType(name.substringAfterLast('.', ""))
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(doc.uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(intent, "Abrir con"))
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "No hay una aplicación compatible para abrir este archivo", Toast.LENGTH_LONG).show()
                    }
                }
            }
            holder.itemView.setOnLongClickListener {
                Toast.makeText(this@MainActivity, doc.uri.toString(), Toast.LENGTH_SHORT).show()
                true
            }
        }
    }

    private fun bindDocumentIcon(name: String, isDirectory: Boolean, imageView: ImageView) {
        if (isDirectory) { imageView.setImageResource(R.drawable.ic_folder); return }
        val extension = name.substringAfterLast('.', "").lowercase()
        imageView.setImageResource(when (extension) {
            "mp4", "mkv", "avi", "mov", "webm" -> R.drawable.ic_video
            "mp3", "flac", "wav", "aac", "ogg" -> R.drawable.ic_music
            "jpg", "jpeg", "png", "webp", "gif" -> R.drawable.ic_image
            "pdf", "docx", "doc", "xlsx", "xls", "pptx", "ppt", "txt" -> R.drawable.ic_doc
            "apk" -> R.drawable.ic_apk
            "zip", "rar", "7z", "tar", "gz" -> R.drawable.ic_archive
            else -> R.drawable.ic_file
        })
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
                    reloadCurrentDirectory()
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
                showStorageChoices()
            }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(drivesButton)
    }

    private fun reloadCurrentDirectory() {
        val external = currentDocumentDir
        if (external != null) loadDocumentDirectory(external, currentFilter) else loadDirectory(currentPath, currentFilter)
    }

    private fun loadDirectory(dir: File, filter: FileType) {
        currentDocumentDir = null
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
        val external = currentDocumentDir
        if (external != null) {
            val parent = external.parentFile
            if (parent != null) loadDocumentDirectory(parent, currentFilter)
            else { currentDocumentDir = null; loadDirectory(File("/storage/emulated/0"), currentFilter) }
            return
        }
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
