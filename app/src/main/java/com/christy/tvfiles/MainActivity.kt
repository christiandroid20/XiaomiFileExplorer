package com.christy.tvfiles

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.text.format.Formatter
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

enum class Mode { PHOTOS, AUDIO, VIDEO, ALL }

class Entry(val file: File?, val name: String, val sub: String, val type: Int, val up: Boolean = false)

class Cat(val mode: Mode, val label: String, val icon: Int, val color: Int)

class CardVH(v: View) : RecyclerView.ViewHolder(v) {
    val thumb: FrameLayout = v.findViewById(R.id.thumb)
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
            h.name.text = e.name
            h.sub.text = e.sub
            h.icon.setImageResource(icons[e.type])
            h.icon.setColorFilter(fgs[e.type])
            val bg = GradientDrawable()
            bg.cornerRadius = dp(10f)
            bg.setColor(bgs[e.type])
            h.thumb.background = bg
            h.itemView.setOnClickListener { onItem(e) }
            h.itemView.setOnFocusChangeListener { v, f -> fx(v, bg, f, 1.06f) }
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

    private fun roots(): List<File> {
        val r = ArrayList<File>()
        r.add(Environment.getExternalStorageDirectory())
        File("/storage").listFiles()?.forEach {
            if (it.name != "emulated" && it.name != "self" && it.isDirectory && it.canRead()) r.add(it)
        }
        return r
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
            roots().forEachIndexed { i, f ->
                items.add(Entry(f, if (i == 0) "Almacenamiento interno" else "USB " + f.name, "Almacenamiento", T_DRIVE))
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

    private fun open(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (ex: Exception) {
            Toast.makeText(this, "No hay una app para abrir este archivo", Toast.LENGTH_LONG).show()
        }
    }
}
