package com.christy.tvfiles

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.ViewOutlineProvider
import java.util.concurrent.Executors
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import android.media.MediaPlayer
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

enum class Mode { PHOTOS, AUDIO, VIDEO, ALL }

class Entry(val file: File?, val name: String, val sub: String, val type: Int, val up: Boolean = false)

class Vol(val dir: File, val label: String)

class Cat(val mode: Mode, val label: String, val icon: Int, val color: Int)

class CardVH(v: View) : RecyclerView.ViewHolder(v) {
    val thumb: FrameLayout = v.findViewById(R.id.thumb)
    val thumbImg: ImageView = v.findViewById(R.id.thumbImg)
    val videoPreview: VideoView = v.findViewById(R.id.videoPreview)
    val icon: ImageView = v.findViewById(R.id.icon)
    val name: TextView = v.findViewById(R.id.name)
    val sub: TextView = v.findViewById(R.id.sub)
}

class MainActivity : Activity() {

    companion object {
        const val T_PHOTO = 0
        const val T_AUDIO = 1
        const val T_VIDEO = 2
        const val T_DIR = 3
        const val T_FILE = 4
        const val T_DRIVE = 5
        const val T_UP = 6
    }

    private val img = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
    private val aud = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "wma", "opus")
    private val vid = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m4v", "mpg", "mpeg", "3gp")

    private val icons = intArrayOf(
        R.drawable.ic_image, R.drawable.ic_music, R.drawable.ic_video, R.drawable.ic_folder,
        R.drawable.ic_file, R.drawable.ic_storage, R.drawable.ic_up
    )
    private val bgs = intArrayOf(
        Color.parseColor("#4A2236"), Color.parseColor("#32285A"), Color.parseColor("#4D2D1C"),
        Color.parseColor("#1C3A52"), Color.parseColor("#2D3038"), Color.parseColor("#1C3A52"),
        Color.parseColor("#2D3038")
    )
    private val fgs = intArrayOf(
        Color.parseColor("#F08BB0"), Color.parseColor("#B9A2F5"), Color.parseColor("#F5A070"),
        Color.parseColor("#7EC4EE"), Color.parseColor("#9AA1AD"), Color.parseColor("#7EC4EE"),
        Color.parseColor("#C8CCD4")
    )

    private val cats = listOf(
        Cat(Mode.PHOTOS, "Fotos", R.drawable.ic_image, Color.parseColor("#FF6700")),
        Cat(Mode.AUDIO, "Audio", R.drawable.ic_music, Color.parseColor("#3E7BFA")),
        Cat(Mode.VIDEO, "Video", R.drawable.ic_video, Color.parseColor("#8B5CF6")),
        Cat(Mode.ALL, "Todos", R.drawable.ic_folder, Color.parseColor("#12B886"))
    )

    private val items = ArrayList<Entry>()
    private val tiles = LinkedHashMap<Mode, View>()
    private val adapter = CardAdapter()
    private val pool = Executors.newFixedThreadPool(3)
    private val previewHandler = Handler(Looper.getMainLooper())
    private val previewTimeouts = java.util.WeakHashMap<VideoView, Runnable>()
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val round = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(10f))
        }
    }
    private lateinit var list: RecyclerView
    private lateinit var t1: TextView
    private lateinit var t2: TextView
    private var mode = Mode.ALL
    private var dir: File? = null
    private var token = 0
    private val perm = android.Manifest.permission.READ_EXTERNAL_STORAGE

    private inner class CardAdapter : RecyclerView.Adapter<CardVH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH =
            CardVH(layoutInflater.inflate(R.layout.item_card, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: CardVH, position: Int) {
            val e = items[position]
            stopVideoPreview(h)
            h.name.text = e.name
            h.sub.text = e.sub
            h.icon.setImageResource(icons[e.type])
            h.icon.setColorFilter(fgs[e.type])
            val bg = GradientDrawable()
            bg.cornerRadius = dp(10f)
            bg.setColor(bgs[e.type])
            h.thumb.background = bg
            val ring = GradientDrawable()
            ring.cornerRadius = dp(10f)
            ring.setColor(Color.TRANSPARENT)
            h.thumb.foreground = ring
            h.thumb.outlineProvider = round
            h.thumb.clipToOutline = true
            loadThumb(h, e)
            h.itemView.setOnClickListener { onItem(e) }
            h.itemView.setOnFocusChangeListener { v, focused ->
                fx(v, ring, focused, 1.06f)
                if (focused && e.type == T_VIDEO && e.file != null) {
                    startVideoPreview(h, e)
                } else {
                    stopVideoPreview(h)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        t1 = findViewById(R.id.t1)
        t2 = findViewById(R.id.t2)
        list.layoutManager = GridLayoutManager(this, 5)
        list.adapter = adapter
        buildBar()
        updateMarks()
        val mf = IntentFilter()
        mf.addAction(Intent.ACTION_MEDIA_MOUNTED)
        mf.addAction(Intent.ACTION_MEDIA_UNMOUNTED)
        mf.addAction(Intent.ACTION_MEDIA_REMOVED)
        mf.addAction(Intent.ACTION_MEDIA_EJECT)
        mf.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
        mf.addDataScheme("file")
        registerReceiver(mediaReceiver, mf)

        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), 1)
        } else {
            showDir(null)
        }
        tiles[Mode.ALL]?.requestFocus()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) showDir(null)
        else t1.text = "Permiso de almacenamiento denegado"
    }

    override fun onBackPressed() {
        val d = dir
        if (mode == Mode.ALL && d != null) goUp(d) else super.onBackPressed()
    }

    private fun dp(x: Float) = x * resources.displayMetrics.density

    private fun fx(v: View, bg: GradientDrawable, focused: Boolean, scale: Float) {
        val s = if (focused) scale else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        bg.setStroke(if (focused) dp(3f).toInt() else 0, Color.WHITE)
    }

    private fun buildBar() {
        val bar = findViewById<LinearLayout>(R.id.bar)
        for (c in cats) {
            val v = layoutInflater.inflate(R.layout.item_tile, bar, false)
            val bg = GradientDrawable()
            bg.cornerRadius = dp(12f)
            bg.setColor(c.color)
            v.findViewById<FrameLayout>(R.id.box).background = bg
            val ic = v.findViewById<ImageView>(R.id.icon)
            ic.setImageResource(c.icon)
            ic.setColorFilter(Color.WHITE)
            v.findViewById<TextView>(R.id.label).text = c.label
            v.setOnClickListener { setMode(c.mode) }
            v.setOnFocusChangeListener { x, f -> fx(x, bg, f, 1.15f) }
            bar.addView(v)
            tiles[c.mode] = v
        }
    }

    private fun updateMarks() {
        for ((m, v) in tiles) {
            val on = m == mode
            v.findViewById<View>(R.id.mark).visibility = if (on) View.VISIBLE else View.INVISIBLE
            v.findViewById<TextView>(R.id.label)
                .setTextColor(if (on) Color.WHITE else Color.parseColor("#8D95A5"))
        }
    }

    private fun roots(): List<File> = volumes().map { it.dir }

    private fun volumeDir(v: StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= 30) return v.directory
        return try {
            File(StorageVolume::class.java.getMethod("getPath").invoke(v) as String)
        } catch (t: Throwable) {
            null
        }
    }

    private fun volumes(): List<Vol> {
        val out = ArrayList<Vol>()
        val seen = HashSet<String>()
        fun add(f: File?, label: String) {
            if (f == null || !f.isDirectory || !f.canRead()) return
            val p = try { f.canonicalPath } catch (t: Throwable) { f.path }
            if (!seen.add(p)) return
            out.add(Vol(f, label))
        }
        add(Environment.getExternalStorageDirectory(), "Almacenamiento interno")
        // 1) volumenes que reporta el sistema (USB, SD), solo los montados
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
                for (v in sm.storageVolumes) {
                    if (v.isPrimary) continue
                    if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) continue
                    val dirV = volumeDir(v) ?: continue
                    val desc = v.getDescription(this)
                    add(dirV, if (desc.isNullOrBlank()) "USB " + dirV.name else desc)
                }
            } catch (t: Throwable) {
            }
        }
        // 2) respaldo: la carpeta de la app que Android crea en cada volumen
        for (f in getExternalFilesDirs(null)) {
            if (f == null) continue
            val i = f.path.indexOf("/Android/data")
            if (i > 0) {
                val root = File(f.path.substring(0, i))
                add(root, "USB " + root.name)
            }
        }
        // 3) respaldo: lo que haya montado en /storage
        File("/storage").listFiles()?.forEach {
            if (it.name != "emulated" && it.name != "self") add(it, "USB " + it.name)
        }
        return out
    }

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            list.postDelayed({ refresh() }, 1200)
        }
    }

    private fun refresh() {
        val d = dir
        if (mode != Mode.ALL) setMode(mode)
        else if (d == null || !d.exists()) showDir(null)
        else showDir(d)
    }

    override fun onDestroy() {
        try { unregisterReceiver(mediaReceiver) } catch (t: Throwable) { }
        super.onDestroy()
    }

    private fun isRoot(d: File) = roots().any { it.path == d.path }

    private fun goUp(d: File) = showDir(if (isRoot(d)) null else d.parentFile)

    private fun typeOf(f: File): Int {
        if (f.isDirectory) return T_DIR
        val e = f.extension.lowercase()
        return when {
            e in img -> T_PHOTO
            e in aud -> T_AUDIO
            e in vid -> T_VIDEO
            else -> T_FILE
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        updateMarks()
        when (m) {
            Mode.PHOTOS -> scan(img, "Fotos")
            Mode.AUDIO -> scan(aud, "Audio")
            Mode.VIDEO -> scan(vid, "Video")
            Mode.ALL -> showDir(null)
        }
    }

    private fun scan(exts: Set<String>, label: String) {
        val t = ++token
        dir = null
        t1.text = label
        t2.text = "Buscando..."
        items.clear()
        adapter.notifyDataSetChanged()
        Thread {
            val found = ArrayList<File>()
            fun walk(d: File) {
                if (t != token) return
                val fs = d.listFiles() ?: return
                for (f in fs) {
                    if (f.isDirectory) {
                        if (!f.name.startsWith(".") && f.name != "Android") walk(f)
                    } else if (f.extension.lowercase() in exts) {
                        found.add(f)
                    }
                }
            }
            roots().forEach { walk(it) }
            found.sortBy { it.name.lowercase() }
            runOnUiThread {
                if (t == token) {
                    items.clear()
                    found.forEach { items.add(Entry(it, it.name, it.parentFile?.name ?: "", typeOf(it))) }
                    adapter.notifyDataSetChanged()
                    t2.text = "${found.size} archivos"
                }
            }
        }.start()
    }

    private fun showDir(d: File?) {
        token++
        dir = d
        items.clear()
        if (d == null) {
            t1.text = "Todos los archivos"
            t2.text = "Almacenamiento"
            for (v in volumes()) {
                val free = Formatter.formatShortFileSize(this, v.dir.freeSpace)
                val total = Formatter.formatShortFileSize(this, v.dir.totalSpace)
                items.add(Entry(v.dir, v.label, "$free libres de $total", T_DRIVE))
            }
        } else {
            t1.text = d.name.ifEmpty { "Almacenamiento" }
            t2.text = d.path
            items.add(Entry(null, "..", "Subir", T_UP, true))
            val fs = d.listFiles()
                ?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
            fs.forEach {
                val sub = if (it.isDirectory) "Carpeta" else Formatter.formatShortFileSize(this, it.length())
                items.add(Entry(it, it.name, sub, typeOf(it)))
            }
        }
        adapter.notifyDataSetChanged()
        list.scrollToPosition(0)
    }

    private fun onItem(e: Entry) {
        if (e.up) {
            dir?.let { goUp(it) }
            return
        }
        val f = e.file ?: return
        if (f.isDirectory) showDir(f) else open(f)
    }

    private fun loadThumb(h: CardVH, e: Entry) {
        h.itemView.tag = e
        h.thumbImg.visibility = View.GONE
        h.icon.visibility = View.VISIBLE
        val f = e.file ?: return
        if (f.isDirectory) return
        val ext = f.extension.lowercase()
        if (ext !in img && ext !in vid && ext !in aud && ext != "apk") return
        val key = f.path + "|" + f.lastModified()
        val hit = cache.get(key)
        if (hit != null) {
            showThumb(h, hit, ext == "apk", false)
            return
        }
        pool.execute {
            if (h.itemView.tag !== e) return@execute
            val b = makeThumb(f, ext)
            if (b != null) {
                cache.put(key, b)
                runOnUiThread { if (h.itemView.tag === e) showThumb(h, b, ext == "apk", true) }
            }
        }
    }

    private fun startVideoPreview(h: CardVH, e: Entry) {
        val file = e.file ?: return
        if (!file.isFile || !file.canRead()) return
        val video = h.videoPreview
        stopVideoPreview(h)
        video.visibility = View.VISIBLE
        h.thumbImg.visibility = View.GONE
        h.icon.visibility = View.GONE
        video.setOnPreparedListener { mp ->
            try {
                mp.isLooping = false
                mp.setVolume(0f, 0f)
                video.seekTo(3000)
                video.start()
                val timeout = Runnable {
                    try { video.stopPlayback() } catch (_: Throwable) { }
                    video.visibility = View.GONE
                    if (h.itemView.hasFocus()) {
                        h.thumbImg.visibility = if (h.thumbImg.drawable != null) View.VISIBLE else View.GONE
                        h.icon.visibility = if (h.thumbImg.drawable != null) View.GONE else View.VISIBLE
                    }
                    previewTimeouts.remove(video)
                }
                previewTimeouts[video] = timeout
                // Reproducir solo 20 segundos y únicamente en la tarjeta enfocada.
                previewHandler.postDelayed(timeout, 20_000L)
            } catch (_: Throwable) {
                stopVideoPreview(h)
            }
        }
        video.setOnErrorListener { _, _, _ ->
            video.visibility = View.GONE
            h.thumbImg.visibility = View.VISIBLE
            h.icon.visibility = View.GONE
            true
        }
        video.setOnCompletionListener {
            stopVideoPreview(h)
            if (h.itemView.hasFocus()) {
                h.thumbImg.visibility = if (h.thumbImg.drawable != null) View.VISIBLE else View.GONE
                h.icon.visibility = if (h.thumbImg.drawable != null) View.GONE else View.VISIBLE
            }
        }
        try {
            video.setVideoPath(file.absolutePath)
        } catch (_: Throwable) {
            video.visibility = View.GONE
        }
    }

    private fun stopVideoPreview(h: CardVH) {
        val video = h.videoPreview
        previewTimeouts.remove(video)?.let { previewHandler.removeCallbacks(it) }
        try { video.stopPlayback() } catch (_: Throwable) { }
        video.visibility = View.GONE
    }

    private fun showThumb(h: CardVH, b: Bitmap, apk: Boolean, fade: Boolean) {
        val p = if (apk) dp(24f).toInt() else 0
        h.thumbImg.setPadding(p, p, p, p)
        h.thumbImg.scaleType = if (apk) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        h.thumbImg.setImageBitmap(b)
        h.thumbImg.visibility = View.VISIBLE
        h.icon.visibility = View.GONE
        if (fade) {
            h.thumbImg.alpha = 0f
            h.thumbImg.animate().alpha(1f).setDuration(150).start()
        } else {
            h.thumbImg.alpha = 1f
        }
    }

    private fun sampleFor(w: Int, h: Int): Int {
        var s = 1
        while (w / (s * 2) >= 640 && h / (s * 2) >= 384) s *= 2
        return s
    }

    private fun makeThumb(f: File, ext: String): Bitmap? {
        try {
            if (ext in img) {
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeFile(f.path, o)
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = sampleFor(o.outWidth, o.outHeight)
                return BitmapFactory.decodeFile(f.path, o2)
            }
            if (ext == "apk") {
                val pm = packageManager
                val info = pm.getPackageArchiveInfo(f.path, 0) ?: return null
                val ai = info.applicationInfo ?: return null
                ai.sourceDir = f.path
                ai.publicSourceDir = f.path
                val d = ai.loadIcon(pm)
                val b = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 192, 192)
                d.draw(Canvas(b))
                return b
            }
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.path)
                if (ext in vid) {
                    val fr = r.getFrameAtTime(3000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: r.frameAtTime ?: return null
                    return Bitmap.createScaledBitmap(fr, 640, 640 * fr.height / fr.width, true)
                }
                val art = r.embeddedPicture ?: return null
                val o = BitmapFactory.Options()
                o.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(art, 0, art.size, o)
                val o2 = BitmapFactory.Options()
                o2.inSampleSize = sampleFor(o.outWidth, o.outHeight)
                return BitmapFactory.decodeByteArray(art, 0, art.size, o2)
            } finally {
                r.release()
            }
        } catch (t: Throwable) {
            return null
        }
    }

    private fun open(f: File) {
        val ext = f.extension.lowercase()
        val viewerType = when {
            ext in img -> "image"
            ext in aud -> "audio"
            ext in vid -> "video"
            else -> null
        }

        // Los formatos multimedia se abren dentro de la app, sin depender de que
        // el TV Box tenga otra galería o reproductor instalado.
        if (viewerType != null) {
            try {
                startActivity(Intent(this, ViewerActivity::class.java)
                    .putExtra("filePath", f.absolutePath)
                    .putExtra("fileType", viewerType))
            } catch (ex: Exception) {
                Toast.makeText(this, "No se pudo abrir este archivo", Toast.LENGTH_LONG).show()
            }
            return
        }

        // Para documentos desconocidos, abrir con una app externa si existe.
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (ex: Exception) {
            Toast.makeText(this, "No hay una app compatible para abrir este archivo", Toast.LENGTH_LONG).show()
        }
    }
}
