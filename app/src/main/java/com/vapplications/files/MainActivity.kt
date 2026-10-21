package com.vapplications.files

import android.app.*
import android.os.*
import android.content.*
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.text.TextUtils
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.DocumentsContract
import android.view.*
import android.widget.*
import java.io.*
import java.util.Locale
import java.util.zip.*
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val bg = Color.rgb(22, 34, 56)
    private val panel = Color.rgb(32, 49, 77)
    private val fg = Color.rgb(238, 247, 255)
    private val muted = Color.rgb(164, 188, 207)
    private val accent = Color.rgb(32, 163, 190)
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroller: ScrollView
    private var gridMode = false
    private var filterMode = 0
    private val thumbnailCache = object : LruCache<String, Bitmap>(48) {}
    private val thumbWorkers = java.util.concurrent.Executors.newFixedThreadPool(3)
    private val favoriteIds = linkedSetOf<String>()
    private val recentIds = mutableListOf<String>()
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var search: EditText
    private var tree: Uri? = null
    private var current: Uri? = null
    private var selected: Uri? = null
    private val multi = linkedSetOf<Uri>()
    private var multiMode = false
    private var refreshToken = 0
    private var rowCache: List<Pair<Uri, Info>> = emptyList()
    private var clipboard: Uri? = null
    private var clipboardItems: List<Uri> = emptyList()
    private var cut = false
    private var sortMode = 0
    private var entries: List<Uri> = emptyList()
    private val pickCode = 200
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val saved = getPreferences(MODE_PRIVATE).getString("tree", null)
        if (saved != null) {
            try { tree = Uri.parse(saved); current = rootDoc(tree!!); queryName(current!!) } catch (_: Exception) { tree = null; current = null }
        }
        favoriteIds.addAll(getPreferences(MODE_PRIVATE).getString("favorites", "")!!.split("\n").filter { it.isNotBlank() })
        recentIds.addAll(getPreferences(MODE_PRIVATE).getString("recent", "")!!.split("\n").filter { it.isNotBlank() })
        draw()
    }
    private fun shape(color: Int, radius: Float = 18f): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = radius * resources.displayMetrics.density }
    private fun aurora(): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(23, 37, 61), Color.rgb(37, 42, 77), Color.rgb(19, 63, 78)))
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun label(s: String, size: Float = 15f, color: Int = fg, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(dp(5), dp(7), dp(5), dp(7))
    }
    private fun button(text: String, action: () -> Unit): TextView = label(text, 13f, fg, true).apply {
        gravity = Gravity.CENTER; background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(42, 119, 194), Color.rgb(28, 167, 184))).apply { cornerRadius = dp(13).toFloat() }; setPadding(dp(9), dp(10), dp(9), dp(10))
        setOnClickListener { if (!busy) { animate().alpha(.6f).setDuration(90).withEndAction { animate().alpha(1f).setDuration(140).start(); action() }.start() } }
    }
    private fun horizontal(vararg buttons: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (b in buttons) addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
    }
    private fun draw() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = aurora(); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        setContentView(root)
        root.addView(label("▱  V File Manager", 21f, fg, true))
        root.addView(label("V APPLICATIONS  •  v0.3.0", 11f, Color.rgb(137, 223, 239)))
        root.addView(horizontal(button("选择文件夹") { chooseTree() }, button("返回上级") { goUp() }, button("刷新") { refresh() }))
        heading = label("尚未授权文件夹", 14f, fg, true); root.addView(heading)
        search = EditText(this).apply {
            hint = "搜索当前文件夹"; setHintTextColor(muted); setTextColor(fg); textSize = 14f; isSingleLine = true
            background = shape(panel); setPadding(dp(12), dp(7), dp(12), dp(7))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { showEntries() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        root.addView(search)
        root.addView(horizontal(button("排序") { sortMode = (sortMode + 1) % 3; showEntries() }, button("筛选") { filterMode = (filterMode + 1) % 5; showEntries(); toast("筛选：" + listOf("全部", "图片", "视频", "音频", "文档")[filterMode]) }, button("列表 / 网格") { gridMode = !gridMode; showEntries() }, button("新建文件夹") { prompt("文件夹名称") { name -> operation { createFolder(name) } } }))
        root.addView(horizontal(button("收藏 / 取消收藏") { toggleFavorite() }, button("收藏夹") { showQuickList(true) }, button("最近访问") { showQuickList(false) }))
        root.addView(horizontal(button("多选 / 完成") { multiMode = !multiMode; if (!multiMode) multi.clear(); showEntries(); updateSelectionStatus() }, button("全选") { multiMode = true; multi.addAll(rowCache.filter { it.second.name.contains(search.text.toString(), true) }.map { it.first }); showEntries(); updateSelectionStatus() }, button("清空选择") { multi.clear(); selected = null; showEntries(); updateSelectionStatus() }))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        scroller = scroll
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(8)) }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("长按多选 · 点击文件可预览 · 通过系统授权访问文件", 11f, muted))
        root.addView(horizontal(button("分享 / 转发") { shareSelected() }, button("查看 / 编辑") { viewSelected() }, button("选择信息") { selectionInfo() }))
        root.addView(horizontal(button("详情") { details() }, button("重命名") { rename() }, button("删除") { deleteSelected() }))
        root.addView(horizontal(button("复制") { copy(false) }, button("剪切") { copy(true) }, button("粘贴到此处") { paste() }))
        root.addView(horizontal(button("压缩 ZIP") { compress() }, button("解压 ZIP") { extract() }))
        status = label("选择一个文件夹开始使用", 12f, muted); root.addView(status)
        refresh()
    }
    private fun chooseTree() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, pickCode)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                tree = uri; current = rootDoc(uri); selected = null; multi.clear()
                getPreferences(MODE_PRIVATE).edit().putString("tree", uri.toString()).apply()
                refresh()
            } catch (e: Exception) { error(e) }
        }
    }
    private fun rootDoc(t: Uri) = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
    private fun doc(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree!!, id)
    private data class Info(val id: String, val name: String, val mime: String, val size: Long, val modified: Long)
    private fun info(uri: Uri): Info {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            if (c.moveToFirst()) return Info(c.getString(0), c.getString(1) ?: "?", c.getString(2) ?: "", if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
        }
        throw IOException("无法读取文件信息")
    }
    private fun isDir(uri: Uri) = info(uri).mime == DocumentsContract.Document.MIME_TYPE_DIR
    private fun queryName(uri: Uri) = info(uri).name
    private fun children(uri: Uri): List<Uri> {
        val id = DocumentsContract.getDocumentId(uri)
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree!!, id)
        val result = mutableListOf<Uri>()
        contentResolver.query(childUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { c -> while (c.moveToNext()) result.add(doc(c.getString(0))) }
        return result
    }
    private fun refresh() {
        val dir = current
        if (dir == null) { heading.text = "尚未授权文件夹"; list.removeAllViews(); status.text = "请先点击「选择文件夹」"; return }
        val token = ++refreshToken
        status.text = "正在加载文件列表…"
        thread {
            val result = try {
                val title = queryName(dir)
                val rows = children(dir).mapNotNull { uri -> try { uri to info(uri) } catch (_: Exception) { null } }
                Triple(title, rows, null as Exception?)
            } catch (e: Exception) { Triple("", emptyList(), e) }
            runOnUiThread {
                if (token != refreshToken || current != dir) return@runOnUiThread
                if (result.third != null) { error(result.third!!); return@runOnUiThread }
                heading.text = "📂  ${result.first}"
                rowCache = result.second
                entries = rowCache.map { it.first }
                multi.retainAll(entries.toSet())
                showEntries()
                updateSelectionStatus()
            }
        }
    }
    private fun updateSelectionStatus() {
        if (::status.isInitialized) status.text = "${entries.size} 个项目 · 已选 ${if (multi.isNotEmpty()) multi.size else if (selected != null) 1 else 0} 项"
    }
    private fun matchesFilter(meta: Info): Boolean {
        if (meta.mime == DocumentsContract.Document.MIME_TYPE_DIR) return true
        val mime = meta.mime.lowercase(Locale.ROOT)
        return when (filterMode) {
            1 -> mime.startsWith("image/")
            2 -> mime.startsWith("video/")
            3 -> mime.startsWith("audio/")
            4 -> mime.startsWith("text/") || mime.contains("pdf") || meta.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("kt", "java", "py", "js", "json", "xml", "md", "html", "txt", "csv")
            else -> true
        }
    }
    private fun showEntries() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val q = if (::search.isInitialized) search.text.toString().trim().lowercase(Locale.ROOT) else ""
        val ordered = rowCache.filter { it.second.name.lowercase(Locale.ROOT).contains(q) && matchesFilter(it.second) }
            .sortedWith(compareBy<Pair<Uri, Info>> { it.second.mime != DocumentsContract.Document.MIME_TYPE_DIR }.thenComparator { a, b ->
                when (sortMode) { 1 -> b.second.size.compareTo(a.second.size); 2 -> b.second.modified.compareTo(a.second.modified); else -> a.second.name.compareTo(b.second.name, ignoreCase = true) }
            })
        val widthDp = resources.configuration.screenWidthDp
        val columns = if (!gridMode) 1 else if (widthDp >= 700) 5 else if (widthDp >= 420) 3 else 2
        val grid = if (gridMode) android.widget.GridLayout(this).apply { columnCount = columns } else null
        if (grid != null) list.addView(grid)
        val cellWidth = (widthDp - 30) / columns - 8
        for ((uri, meta) in ordered) {
            val folder = meta.mime == DocumentsContract.Document.MIME_TYPE_DIR
            val checked = uri in multi || (multi.isEmpty() && uri == selected)
            val row = LinearLayout(this).apply { orientation = if (gridMode) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = shape(if (checked) accent else panel); setPadding(dp(9), dp(8), dp(9), dp(8)) }
            val glyph = label(if (multiMode) (if (uri in multi) "☑" else "☐") else if (folder) "▱" else "▤", if (gridMode) 27f else 23f, Color.rgb(147, 230, 243), true)
            glyph.gravity = Gravity.CENTER
            val thumb = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
            val name = label(meta.name + (if (folder) "" else "\n${formatSize(meta.size)}"), if (gridMode) 12f else 14f, fg)
            name.maxLines = if (gridMode) 3 else 2; name.ellipsize = TextUtils.TruncateAt.END
            if (gridMode) {
                row.addView(glyph, LinearLayout.LayoutParams(-1, dp(48)))
                row.addView(thumb, LinearLayout.LayoutParams(-1, dp(65)))
                row.addView(name, LinearLayout.LayoutParams(-1, dp(65)))
            } else {
                row.addView(glyph, LinearLayout.LayoutParams(dp(48), dp(55)))
                row.addView(thumb, LinearLayout.LayoutParams(dp(52), dp(52)))
                row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            }
            if (!folder && meta.mime.startsWith("image/") && meta.size in 1..(15L * 1024 * 1024)) loadThumbnail(uri, thumb, glyph)
            row.setOnClickListener {
                if (multiMode) { if (!multi.add(uri)) multi.remove(uri); selected = null; showEntries(); updateSelectionStatus() }
                else if (folder) { current = uri; selected = null; multi.clear(); search.setText(""); refresh() }
                else { selected = uri; trackRecent(uri); previewFile(uri, meta) }
            }
            row.setOnLongClickListener { multiMode = true; multi.add(uri); selected = null; showEntries(); updateSelectionStatus(); true }
            if (grid != null) grid.addView(row, android.widget.GridLayout.LayoutParams().apply { width = dp(cellWidth); height = -2; setMargins(dp(3), dp(3), dp(3), dp(3)) })
            else list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }
        if (ordered.isEmpty()) list.addView(label("这里没有匹配的文件", 14f, muted))
    }
    private fun loadThumbnail(uri: Uri, image: ImageView, glyph: TextView) {
        val key = uri.toString()
        val cached = thumbnailCache.get(key)
        if (cached != null) { image.setImageBitmap(cached); image.visibility = View.VISIBLE; glyph.visibility = View.GONE; return }
        image.tag = key
        thumbWorkers.execute {
            val bitmap = try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytesLimited(15 * 1024 * 1024)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    options.inSampleSize = maxOf(1, maxOf(options.outWidth, options.outHeight) / 160)
                    options.inJustDecodeBounds = false
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
            } catch (_: Exception) { null }
            if (bitmap != null) runOnUiThread {
                thumbnailCache.put(key, bitmap)
                if (image.tag == key && image.parent != null) { image.setImageBitmap(bitmap); image.visibility = View.VISIBLE; glyph.visibility = View.GONE }
            }
        }
    }
    override fun onDestroy() { thumbWorkers.shutdownNow(); super.onDestroy() }
    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) { val n = read(buffer); if (n < 0) break; if (out.size() + n > limit) throw IOException("文件太大"); out.write(buffer, 0, n) }
        return out.toByteArray()
    }
    private fun trackRecent(uri: Uri) {
        val key = uri.toString(); recentIds.remove(key); recentIds.add(0, key)
        while (recentIds.size > 30) recentIds.removeAt(recentIds.lastIndex)
        getPreferences(MODE_PRIVATE).edit().putString("recent", recentIds.joinToString("\n")).apply()
    }
    private fun toggleFavorite() {
        val uri = chosen() ?: return
        val key = uri.toString()
        if (!favoriteIds.add(key)) favoriteIds.remove(key)
        getPreferences(MODE_PRIVATE).edit().putString("favorites", favoriteIds.joinToString("\n")).apply()
        toast(if (key in favoriteIds) "已收藏" else "已取消收藏")
    }
    private fun showQuickList(favorites: Boolean) {
        val saved = if (favorites) favoriteIds.toList() else recentIds.toList()
        if (saved.isEmpty()) { toast("暂无记录"); return }
        thread {
            val rows = saved.mapNotNull { key -> try { val uri = Uri.parse(key); uri to info(uri) } catch (_: Exception) { null } }
            runOnUiThread {
                if (rows.isEmpty()) { toast("记录中的文件可能已被删除或权限失效"); return@runOnUiThread }
                AlertDialog.Builder(this).setTitle(if (favorites) "收藏夹" else "最近访问")
                    .setItems(rows.map { it.second.name }.toTypedArray()) { _, index ->
                        val (uri, meta) = rows[index]
                        if (meta.mime == DocumentsContract.Document.MIME_TYPE_DIR) { current = uri; search.setText(""); refresh() }
                        else { selected = uri; trackRecent(uri); previewFile(uri, meta) }
                    }.setNegativeButton("关闭", null).show()
            }
        }
    }
    private fun previewFile(uri: Uri, meta: Info) {
        if (meta.mime.startsWith("image/")) {
            thread {
                val bmp = try { contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytesLimited(24 * 1024 * 1024)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    options.inSampleSize = maxOf(1, maxOf(options.outWidth, options.outHeight) / 1600)
                    options.inJustDecodeBounds = false
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                } } catch (_: Exception) { null }
                runOnUiThread {
                    if (bmp == null) { openFile(uri); return@runOnUiThread }
                    val image = ImageView(this).apply { setImageBitmap(bmp); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
                    val wrapper = ScrollView(this).apply { setPadding(dp(12), dp(8), dp(12), dp(8)); addView(image) }
                    AlertDialog.Builder(this).setTitle(meta.name).setView(wrapper).setNegativeButton("关闭", null).setPositiveButton("外部打开") { _, _ -> openFile(uri) }.show()
                }
            }
        } else if (meta.mime.startsWith("text/") || meta.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("txt", "md", "json", "xml", "kt", "java", "py", "js", "html", "css", "csv")) viewSelected()
        else openFile(uri)
    }
    private fun formatSize(n: Long): String = if (n < 1024) "$n B" else if (n < 1048576) "%.1f KB".format(Locale.US, n / 1024.0) else "%.1f MB".format(Locale.US, n / 1048576.0)
    private fun openFile(uri: Uri) {
        try {
            val mime = info(uri).mime.ifBlank { "*/*" }
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { Toast.makeText(this, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show() }
    }
    private fun goUp() {
        val cur = current ?: return
        if (cur == rootDoc(tree!!)) return
        val id = DocumentsContract.getDocumentId(cur)
        val rootId = DocumentsContract.getTreeDocumentId(tree!!)
        val parent = id.substringBeforeLast('/', rootId)
        current = doc(if (parent.startsWith(rootId)) parent else rootId)
        selected = null; multi.clear(); search.setText(""); refresh()
    }
    private fun chosen(): Uri? = (multi.firstOrNull() ?: selected).also { if (it == null) toast("请先选中项目") }
    private fun chosenAll(): List<Uri> = if (multi.isNotEmpty()) multi.toList() else listOfNotNull(selected)
    private fun selectionInfo() { toast("当前已选择 ${chosenAll().size} 项") }
    private fun shareSelected() {
        val items = chosenAll().filter { uri -> rowCache.firstOrNull { it.first == uri }?.second?.mime != DocumentsContract.Document.MIME_TYPE_DIR }
        if (items.isEmpty()) { toast("请先选择要分享的文件（暂不支持直接分享文件夹）"); return }
        val intent = if (items.size == 1) Intent(Intent.ACTION_SEND).apply {
            type = rowCache.firstOrNull { it.first == items[0] }?.second?.mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, items[0]); clipData = ClipData.newUri(contentResolver, "file", items[0])
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items))
            val clip = ClipData.newUri(contentResolver, "files", items[0]); items.drop(1).forEach { clip.addItem(ClipData.Item(it)) }; clipData = clip
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(intent, "分享文件")) } catch (e: Exception) { error(e) }
    }
    private fun viewSelected() {
        val uri = chosen() ?: return
        val meta = rowCache.firstOrNull { it.first == uri }?.second ?: try { info(uri) } catch (_: Exception) { toast("文件权限已失效"); return }
        if (meta.mime == DocumentsContract.Document.MIME_TYPE_DIR) { toast("请选择一个文件"); return }
        if (meta.size > 1024 * 1024) { toast("内置文本查看限 1 MB，大文件请直接点击打开"); return }
        thread {
            val result = try { contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: throw IOException("无法读取") } catch (e: Exception) { runOnUiThread { error(e) }; return@thread }
            runOnUiThread {
                val editor = EditText(this).apply { setText(result); setTextColor(Color.BLACK); textSize = 13f; setPadding(dp(12), dp(8), dp(12), dp(8)); gravity = Gravity.TOP; minLines = 12; maxLines = 22; setHorizontallyScrolling(true) }
                val wrapper = ScrollView(this).apply { addView(editor) }
                AlertDialog.Builder(this).setTitle(meta.name).setView(wrapper).setNegativeButton("关闭", null).setNeutralButton("外部打开") { _, _ -> openFile(uri) }.setPositiveButton("保存修改") { _, _ ->
                    AlertDialog.Builder(this).setTitle("覆盖文件内容？").setMessage("保存会替换原文件内容，请确保已备份重要文件。")
                        .setNegativeButton("取消", null).setPositiveButton("确认保存") { _, _ -> operation { contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(editor.text.toString()) } ?: throw IOException("无法写入文件") } }.show()
                }.show()
            }
        }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun error(e: Exception) { status.text = "失败：${e.message ?: e.javaClass.simpleName}"; toast(status.text.toString()) }
    private fun prompt(title: String, action: (String) -> Unit) {
        val field = EditText(this).apply { setSingleLine(true); setTextColor(Color.BLACK) }
        AlertDialog.Builder(this).setTitle(title).setView(field).setNegativeButton("取消", null).setPositiveButton("确定") { _, _ ->
            val name = field.text.toString().trim()
            if (validName(name)) action(name) else toast("名称无效，不能包含 / 或 \\ ")
        }.show()
    }
    private fun validName(s: String) = s.isNotBlank() && s != "." && s != ".." && '/' !in s && '\\' !in s && '\u0000' !in s
    private fun operation(job: () -> Unit) {
        if (busy) return
        busy = true; status.text = "正在处理，请稍候…"
        thread {
            val failure = try { job(); null } catch (e: Exception) { e }
            runOnUiThread { busy = false; selected = null; multi.clear(); refresh(); if (failure != null) error(failure) else { status.text = "操作完成"; toast("操作完成") } }
        }
    }
    private fun createFolder(name: String) {
        val parent = current ?: throw IOException("尚未选择文件夹")
        if (children(parent).any { queryName(it) == name }) throw IOException("同名文件已存在")
        DocumentsContract.createDocument(contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: throw IOException("无法创建文件夹")
    }
    private fun rename() {
        val uri = chosen() ?: return
        prompt("重命名：${queryName(uri)}") { name -> operation { DocumentsContract.renameDocument(contentResolver, uri, name) ?: throw IOException("无法重命名") } }
    }
    private fun deleteSelected() {
        val targets = chosenAll()
        if (targets.isEmpty()) { toast("请先选择项目"); return }
        AlertDialog.Builder(this).setTitle("确认删除 ${targets.size} 项？").setMessage("删除操作可能无法撤销，请确认已备份重要文件。")
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ -> operation {
                for (uri in targets) if (!DocumentsContract.deleteDocument(contentResolver, uri)) throw IOException("删除失败：${queryName(uri)}")
                multi.clear()
            } }.show()
    }
    private fun details() {
        val uri = chosen() ?: return
        try { val m = info(uri); AlertDialog.Builder(this).setTitle(m.name).setMessage("类型：${m.mime}\n大小：${formatSize(m.size)}\n修改时间：${if (m.modified > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(m.modified)) else "未知"}\n文档 ID：${m.id}").setPositiveButton("关闭", null).show() } catch (e: Exception) { error(e) }
    }
    private fun copy(move: Boolean) {
        clipboardItems = chosenAll()
        if (clipboardItems.isEmpty()) { toast("请先选择项目"); return }
        cut = move
        toast("已${if (move) "剪切" else "复制"} ${clipboardItems.size} 项，进入目标文件夹后点击粘贴")
    }
    private fun paste() {
        val sources = clipboardItems
        if (sources.isEmpty()) { toast("剪贴板为空"); return }
        val dest = current ?: return
        for (source in sources) {
            if (source == dest || (isDir(source) && DocumentsContract.getDocumentId(dest).startsWith(DocumentsContract.getDocumentId(source) + "/"))) { toast("不能粘贴到自身或子文件夹"); return }
        }
        operation {
            for (source in sources) {
                copyRecursive(source, dest, 0)
                if (cut && !DocumentsContract.deleteDocument(contentResolver, source)) throw IOException("复制完成，但删除原文件失败")
            }
            if (cut) clipboardItems = emptyList()
            multi.clear()
        }
    }
    private fun uniqueName(parent: Uri, wanted: String): String {
        val used = children(parent).map { queryName(it) }.toSet()
        if (wanted !in used) return wanted
        val base = wanted.substringBeforeLast('.', wanted)
        val ext = if ('.' in wanted) "." + wanted.substringAfterLast('.') else ""
        for (i in 1..999) { val candidate = "$base ($i)$ext"; if (candidate !in used) return candidate }
        throw IOException("目标文件夹重名文件过多")
    }
    private fun copyRecursive(src: Uri, parent: Uri, depth: Int) {
        if (depth > 32) throw IOException("文件夹层级过深")
        val m = info(src)
        val folder = m.mime == DocumentsContract.Document.MIME_TYPE_DIR
        val target = DocumentsContract.createDocument(contentResolver, parent, if (folder) DocumentsContract.Document.MIME_TYPE_DIR else m.mime.ifBlank { "application/octet-stream" }, uniqueName(parent, m.name)) ?: throw IOException("无法创建 ${m.name}")
        if (folder) children(src).forEach { copyRecursive(it, target, depth + 1) }
        else contentResolver.openInputStream(src).use { input -> contentResolver.openOutputStream(target, "w").use { output -> if (input == null || output == null) throw IOException("无法读写 ${m.name}"); input.copyTo(output) } }
    }
    private fun compress() {
        val sources = chosenAll()
        if (sources.isEmpty()) { toast("请先选择项目"); return }
        val parent = current ?: return
        operation {
            val name = uniqueName(parent, if (sources.size == 1) queryName(sources[0]).substringBeforeLast('.', queryName(sources[0])) + ".zip" else "Archive.zip")
            val output = DocumentsContract.createDocument(contentResolver, parent, "application/zip", name) ?: throw IOException("无法创建 ZIP")
            try {
                contentResolver.openOutputStream(output, "w").use { raw ->
                    if (raw == null) throw IOException("无法写入 ZIP")
                    ZipOutputStream(BufferedOutputStream(raw)).use { zip -> sources.forEach { addZip(it, "", zip, 0) } }
                }
            } catch (e: Exception) { DocumentsContract.deleteDocument(contentResolver, output); throw e }
        }
    }
    private fun addZip(uri: Uri, prefix: String, zip: ZipOutputStream, depth: Int) {
        if (depth > 32) throw IOException("层级过深")
        val m = info(uri); val path = prefix + m.name
        if (m.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            zip.putNextEntry(ZipEntry("$path/")); zip.closeEntry()
            children(uri).forEach { addZip(it, "$path/", zip, depth + 1) }
        } else {
            zip.putNextEntry(ZipEntry(path))
            contentResolver.openInputStream(uri).use { input -> if (input == null) throw IOException("无法读取 ${m.name}"); input.copyTo(zip) }
            zip.closeEntry()
        }
    }
    private fun extract() {
        val src = chosen() ?: return
        if (!queryName(src).lowercase(Locale.ROOT).endsWith(".zip")) { toast("请选择 ZIP 文件"); return }
        val dest = current ?: return
        AlertDialog.Builder(this).setTitle("解压 ZIP").setMessage("将解压到当前文件夹中的新子文件夹。")
            .setNegativeButton("取消", null).setPositiveButton("解压") { _, _ -> operation { extractZip(src, dest) } }.show()
    }
    private fun extractZip(src: Uri, dest: Uri) {
        val folderName = uniqueName(dest, queryName(src).substringBeforeLast('.'))
        val outRoot = DocumentsContract.createDocument(contentResolver, dest, DocumentsContract.Document.MIME_TYPE_DIR, folderName) ?: throw IOException("无法创建目标文件夹")
        var total = 0L; var count = 0
        try {
            contentResolver.openInputStream(src).use { raw ->
                if (raw == null) throw IOException("无法读取 ZIP")
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        count++; if (count > 5000) throw IOException("ZIP 文件数量超过安全限制")
                        val name = entry.name.replace('\\', '/')
                        val parts = name.split('/').filter { it.isNotEmpty() }
                        if (name.startsWith('/') || parts.any { it == ".." || it == "." || !validName(it) } || parts.size > 32 || ':' in name) throw IOException("ZIP 包含不安全路径")
                        if (parts.isNotEmpty()) {
                            var parent = outRoot
                            for (part in parts.dropLast(1)) {
                                val existing = children(parent).firstOrNull { queryName(it) == part }
                                parent = if (existing == null) DocumentsContract.createDocument(contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, part) ?: throw IOException("无法创建目录") else {
                                    if (!isDir(existing)) throw IOException("文件夹名称冲突"); existing
                                }
                            }
                            val last = parts.last()
                            if (entry.isDirectory) {
                                if (children(parent).none { queryName(it) == last }) DocumentsContract.createDocument(contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, last) ?: throw IOException("无法创建目录")
                            } else {
                                val output = DocumentsContract.createDocument(contentResolver, parent, "application/octet-stream", uniqueName(parent, last)) ?: throw IOException("无法创建文件")
                                contentResolver.openOutputStream(output, "w").use { stream ->
                                    if (stream == null) throw IOException("无法写入文件")
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val n = zip.read(buffer)
                                        if (n < 0) break
                                        total += n
                                        if (total > 512L * 1024 * 1024) throw IOException("ZIP 解压超过 512 MB 安全限制")
                                        stream.write(buffer, 0, n)
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
        } catch (e: Exception) { try { DocumentsContract.deleteDocument(contentResolver, outRoot) } catch (_: Exception) {}; throw e }
    }
}
