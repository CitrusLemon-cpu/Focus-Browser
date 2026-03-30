package com.example.focuslock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.focuslock.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var desktopMode = false
    private var youtubeFocusMode = false
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val expandedFolderIds = mutableSetOf<String>()
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val results: Array<Uri>? = when {
                    data?.clipData != null -> {
                        Array(data.clipData!!.itemCount) { i -> data.clipData!!.getItemAt(i).uri }
                    }
                    data?.data != null -> {
                        arrayOf(data.data!!)
                    }
                    else -> null
                }
                fileUploadCallback?.onReceiveValue(results ?: arrayOf())
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
            fileUploadCallback = null
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true

        binding.webView.settings.safeBrowsingEnabled = true
        binding.webView.settings.allowFileAccess = true
        binding.webView.settings.allowContentAccess = true
        binding.webView.settings.setGeolocationEnabled(false)
        binding.webView.settings.databaseEnabled = false

        val prefs = getSharedPreferences("focus_lock_prefs", Context.MODE_PRIVATE)
        desktopMode = prefs.getBoolean("desktop_mode", false)
        youtubeFocusMode = prefs.getBoolean("youtube_focus_mode", false)
        applyDesktopMode()

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (request.isForMainFrame.not()) return false

                if (url.scheme == "http") {
                    val httpsUrl = url.buildUpon().scheme("https").build().toString()
                    view?.post { view.loadUrl(httpsUrl) }
                    return true
                }

                val urlString = url.toString()
                return if (WhitelistManager.isUrlAllowed(this@MainActivity, urlString)) {
                    false
                } else {
                    view?.post {
                        showHome()
                        showBlockedDialog(urlString)
                    }
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url == "about:blank") {
                    view?.clearHistory()
                } else {
                    url?.let { binding.urlBar.setText(it) }
                }
                if (youtubeFocusMode && url != null && isYouTubeWatchPage(url)) {
                    injectYouTubeFocusCss(view)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    if (!WhitelistManager.isUrlAllowed(this@MainActivity, url)) {
                        view?.post {
                            showHome()
                            showBlockedDialog(url)
                        }
                    }
                    if (youtubeFocusMode && isYouTubeWatchPage(url)) {
                        view?.post { injectYouTubeFocusCss(view) }
                    }
                }
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                callback?.invoke(origin, false, false)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (fullscreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback

                binding.fullscreenContainer.addView(view)
                binding.fullscreenContainer.visibility = View.VISIBLE

                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }

            override fun onHideCustomView() {
                exitFullscreen()
            }
        }

        binding.urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                val input = binding.urlBar.text.toString().trim()
                if (input.isNotEmpty()) {
                    navigateToInput(input)
                }
                true
            } else false
        }

        showHome()

        binding.btnHome.setOnClickListener {
            showHome()
        }

        binding.btnDesktopMode.setOnClickListener {
            desktopMode = !desktopMode
            getSharedPreferences("focus_lock_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("desktop_mode", desktopMode)
                .apply()
            applyDesktopMode()
            if (binding.webView.visibility == View.VISIBLE && binding.webView.url != "about:blank") {
                binding.webView.reload()
            }
        }

        binding.fab.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.fabNewFolder.setOnClickListener {
            showCreateFolderDialog()
        }
    }

    private fun applyDesktopMode() {
        val settings = binding.webView.settings
        if (desktopMode) {
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        } else {
            settings.userAgentString = WebSettings.getDefaultUserAgent(this)
        }
        settings.useWideViewPort = desktopMode
        settings.loadWithOverviewMode = desktopMode
        updateDesktopModeIcon()
    }

    private fun updateDesktopModeIcon() {
        if (desktopMode) {
            val color = android.R.attr.colorPrimary
            val typedValue = android.util.TypedValue()
            theme.resolveAttribute(color, typedValue, true)
            val colorInt = typedValue.data
            binding.btnDesktopMode.colorFilter = PorterDuffColorFilter(colorInt, PorterDuff.Mode.SRC_IN)
        } else {
            binding.btnDesktopMode.clearColorFilter()
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("focus_lock_prefs", Context.MODE_PRIVATE)
        youtubeFocusMode = prefs.getBoolean("youtube_focus_mode", false)
        if (binding.homeScreen.visibility == View.VISIBLE) {
            refreshHomeList()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        binding.webView.clearCache(true)
        binding.webView.clearFormData()
        binding.webView.clearHistory()
    }

    private fun navigateToInput(input: String) {
        val url = if (input.contains(".") && !input.contains(" ")) {
            if (input.startsWith("http://") || input.startsWith("https://")) input
            else "https://$input"
        } else {
            "https://www.google.com/search?q=${java.net.URLEncoder.encode(input, "UTF-8")}"
        }
        hideKeyboard()
        showWebView()
        binding.webView.loadUrl(url)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
    }

    private fun showHome() {
        binding.webView.stopLoading()
        binding.webView.loadUrl("about:blank")
        binding.homeScreen.visibility = View.VISIBLE
        binding.webView.visibility = View.GONE
        binding.fab.visibility = View.VISIBLE
        binding.fabNewFolder.visibility = View.VISIBLE
        binding.urlBar.setText("")
        refreshHomeList()
    }

    private fun showWebView() {
        binding.homeScreen.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.fab.visibility = View.GONE
        binding.fabNewFolder.visibility = View.GONE
    }

    private fun buildItemList(): List<HomeItem> {
        val items = mutableListOf<HomeItem>()
        fun addChildren(parentFolderId: String?, depth: Int) {
            val subfolders = WhitelistManager.getSubfolders(this, parentFolderId)
            for (folder in subfolders) {
                val isExpanded = folder.id in expandedFolderIds
                items.add(HomeItem.FolderItem(folder, depth, isExpanded))
                if (isExpanded) {
                    addChildren(folder.id, depth + 1)
                }
            }
            val entries = WhitelistManager.getEntriesInFolder(this, parentFolderId)
            for (entry in entries) {
                items.add(HomeItem.EntryItem(entry, depth))
            }
        }
        addChildren(null, 0)
        return items
    }

    private fun refreshHomeList() {
        val items = buildItemList()
        if (items.isEmpty()) {
            binding.homeList.visibility = View.GONE
            binding.emptyMessage.visibility = View.VISIBLE
            binding.emptyMessage.text = "No sites added yet.\nTap the \u2699 button to add sites in Settings."
        } else {
            binding.homeList.visibility = View.VISIBLE
            binding.emptyMessage.visibility = View.GONE
            if (binding.homeList.layoutManager == null) {
                binding.homeList.layoutManager = LinearLayoutManager(this)
            }
            val adapter = HomeAdapter(
                items,
                onFolderClick = { folder ->
                    if (folder.id in expandedFolderIds) {
                        expandedFolderIds.remove(folder.id)
                    } else {
                        expandedFolderIds.add(folder.id)
                    }
                    refreshHomeList()
                },
                onFolderRename = { folder -> showRenameFolderDialog(folder) },
                onFolderDelete = { folder -> showDeleteFolderDialog(folder) },
                onEntryClick = { entry ->
                    val url = if (entry.url.startsWith("http://") || entry.url.startsWith("https://")) entry.url
                    else "https://${entry.url}"
                    showWebView()
                    binding.webView.loadUrl(url)
                },
                onEntryRename = { entry -> showRenameDialog(entry) },
                onEntryDelete = { entry ->
                    AlertDialog.Builder(this)
                        .setTitle("Remove Entry")
                        .setMessage("Are you sure you want to remove ${entry.name}?")
                        .setPositiveButton("Remove") { _, _ ->
                            WhitelistManager.removeEntry(this, entry.url)
                            refreshHomeList()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            )
            binding.homeList.adapter = adapter
            setupDragDrop(adapter)
        }
    }

    private fun setupDragDrop(adapter: HomeAdapter) {
        val activity = this
        val callback = object : ItemTouchHelper.Callback() {

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
                return makeMovementFlags(dragFlags, 0)
            }

            override fun isLongPressDragEnabled() = true

            override fun onMove(
                recyclerView: RecyclerView,
                source: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = source.adapterPosition
                val toPos = target.adapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
                adapter.moveItem(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    val pos = viewHolder.adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        adapter.setDragStartItem(adapter.getItems().getOrNull(pos))
                    }
                    viewHolder.itemView.alpha = 0.7f
                    viewHolder.itemView.elevation = 8f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.elevation = 0f

                val pos = viewHolder.adapterPosition
                if (pos == RecyclerView.NO_POSITION) return

                val items = adapter.getItems()
                val draggedItem = adapter.getDraggedItem() ?: return
                val targetItem = items.getOrNull(pos)

                if (targetItem is HomeItem.FolderItem && draggedItem != targetItem) {
                    when (draggedItem) {
                        is HomeItem.EntryItem -> {
                            WhitelistManager.moveEntryToFolder(activity, draggedItem.entry.url, targetItem.folder.id)
                            expandedFolderIds.add(targetItem.folder.id)
                        }
                        is HomeItem.FolderItem -> {
                            if (!isDescendant(draggedItem.folder.id, targetItem.folder.id)) {
                                WhitelistManager.moveFolderToParent(activity, draggedItem.folder.id, targetItem.folder.id)
                                expandedFolderIds.add(targetItem.folder.id)
                            }
                        }
                    }
                } else {
                    persistCurrentOrder(adapter)
                }

                adapter.setDragStartItem(null)
                refreshHomeList()
            }
        }

        val touchHelper = ItemTouchHelper(callback)
        touchHelper.attachToRecyclerView(binding.homeList)
    }

    private fun isDescendant(folderId: String, potentialParentId: String): Boolean {
        if (folderId == potentialParentId) return true
        val folders = WhitelistManager.getFolders(this)
        fun check(currentId: String): Boolean {
            return folders.filter { it.parentId == currentId }.any { it.id == potentialParentId || check(it.id) }
        }
        return check(folderId)
    }

    private fun persistCurrentOrder(adapter: HomeAdapter) {
        val items = adapter.getItems()
        val foldersByParent = mutableMapOf<String?, MutableList<String>>()
        val entriesByFolder = mutableMapOf<String?, MutableList<String>>()

        for (item in items) {
            when (item) {
                is HomeItem.FolderItem -> {
                    foldersByParent.getOrPut(item.folder.parentId) { mutableListOf() }.add(item.folder.id)
                }
                is HomeItem.EntryItem -> {
                    entriesByFolder.getOrPut(item.entry.folderId) { mutableListOf() }.add(item.entry.url)
                }
            }
        }

        for ((parentId, ids) in foldersByParent) {
            WhitelistManager.reorderFolders(this, parentId, ids)
        }
        for ((folderId, urls) in entriesByFolder) {
            WhitelistManager.reorderEntries(this, folderId, urls)
        }
    }

    private fun showCreateFolderDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        val input = EditText(this).apply {
            hint = "Folder name"
        }
        layout.addView(input)

        AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    WhitelistManager.createFolder(this, name, null)
                    refreshHomeList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameFolderDialog(folder: Folder) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        val input = EditText(this).apply {
            hint = "Folder name"
            setText(folder.name)
        }
        layout.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Rename Folder")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    WhitelistManager.renameFolder(this, folder.id, newName)
                    refreshHomeList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteFolderDialog(folder: Folder) {
        val allFolders = WhitelistManager.getFolders(this)
        fun countEntries(folderId: String): Int {
            val direct = WhitelistManager.getEntriesInFolder(this, folderId).size
            val childFolders = allFolders.filter { it.parentId == folderId }
            return direct + childFolders.sumOf { countEntries(it.id) }
        }
        val entryCount = countEntries(folder.id)
        val message = if (entryCount > 0) {
            "Delete \"${folder.name}\" and its $entryCount site(s)? Those sites will be blocked again."
        } else {
            "Delete empty folder \"${folder.name}\"?"
        }

        AlertDialog.Builder(this)
            .setTitle("Delete Folder")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                expandedFolderIds.remove(folder.id)
                WhitelistManager.deleteFolder(this, folder.id)
                refreshHomeList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(entry: WhitelistEntry) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        val input = EditText(this).apply {
            hint = "Display name"
            setText(entry.name)
        }
        layout.addView(input)

        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    WhitelistManager.updateEntryName(this, entry.url, newName)
                    refreshHomeList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBlockedDialog(url: String) {
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        AlertDialog.Builder(this)
            .setTitle("URL Blocked")
            .setMessage(url)
            .setPositiveButton("Copy") { _, _ ->
                val clip = android.content.ClipData.newPlainText("Blocked URL", url)
                clipboard.setPrimaryClip(clip)
                android.widget.Toast.makeText(this, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    private fun exitFullscreen() {
        fullscreenView?.let {
            binding.fullscreenContainer.removeView(it)
        }
        binding.fullscreenContainer.visibility = View.GONE
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun isYouTubeWatchPage(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains("youtube.com/watch") || lower.contains("youtu.be/"))
    }

    private fun injectYouTubeFocusCss(view: WebView?) {
        val css = """
            #related, #secondary, #secondary-inner, ytd-watch-next-secondary-results-renderer,
            #comments, ytd-comments,
            .ytp-endscreen-content, .ytp-ce-element, .ytp-suggestion-set,
            #chip-bar, ytd-feed-filter-chip-bar-renderer {
                display: none !important;
            }
        """.trimIndent().replace("\n", " ").replace("\"", "\\\"")
        val js = "(function() { var style = document.createElement('style'); style.id = 'yt-focus-mode'; style.textContent = "$css"; if (!document.getElementById('yt-focus-mode')) { document.head.appendChild(style); } })()"
        view?.evaluateJavascript(js, null)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (fullscreenView != null) {
            exitFullscreen()
            return
        }
        if (binding.webView.visibility == View.VISIBLE) {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                showHome()
            }
        } else {
            super.onBackPressed()
        }
    }

    private sealed class HomeItem {
        data class FolderItem(val folder: Folder, val depth: Int, val isExpanded: Boolean) : HomeItem()
        data class EntryItem(val entry: WhitelistEntry, val depth: Int) : HomeItem()
    }

    private class HomeAdapter(
        items: List<HomeItem>,
        private val onFolderClick: (Folder) -> Unit,
        private val onFolderRename: (Folder) -> Unit,
        private val onFolderDelete: (Folder) -> Unit,
        private val onEntryClick: (WhitelistEntry) -> Unit,
        private val onEntryRename: (WhitelistEntry) -> Unit,
        private val onEntryDelete: (WhitelistEntry) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val currentItems: MutableList<HomeItem> = items.toMutableList()
        private var dragStartItem: HomeItem? = null

        fun getItems(): List<HomeItem> = currentItems.toList()
        fun getDraggedItem(): HomeItem? = dragStartItem
        fun setDragStartItem(item: HomeItem?) { dragStartItem = item }

        fun moveItem(fromPos: Int, toPos: Int) {
            if (fromPos < 0 || toPos < 0 || fromPos >= currentItems.size || toPos >= currentItems.size) return
            val item = currentItems.removeAt(fromPos)
            currentItems.add(toPos, item)
            notifyItemMoved(fromPos, toPos)
        }

        companion object {
            private const val VIEW_TYPE_FOLDER = 0
            private const val VIEW_TYPE_ENTRY = 1
        }

        class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val chevronView: TextView = view.findViewById(android.R.id.toggle)
            val iconView: ImageView = view.findViewById(android.R.id.icon)
            val textView: TextView = view.findViewById(android.R.id.text1)
            val editButton: ImageButton = view.findViewById(android.R.id.edit)
            val deleteButton: ImageButton = view.findViewById(android.R.id.button1)
        }

        class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(android.R.id.text1)
            val editButton: ImageButton = view.findViewById(android.R.id.edit)
            val deleteButton: ImageButton = view.findViewById(android.R.id.button1)
        }

        override fun getItemViewType(position: Int): Int {
            return when (currentItems[position]) {
                is HomeItem.FolderItem -> VIEW_TYPE_FOLDER
                is HomeItem.EntryItem -> VIEW_TYPE_ENTRY
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                VIEW_TYPE_FOLDER -> {
                    val layout = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setPadding(48, 24, 48, 24)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val chevron = TextView(parent.context).apply {
                        id = android.R.id.toggle
                        textSize = 14f
                        val size = (24 * parent.context.resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    val icon = ImageView(parent.context).apply {
                        id = android.R.id.icon
                        setImageResource(android.R.drawable.ic_menu_agenda)
                        val size = (32 * parent.context.resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            marginEnd = 16
                        }
                    }
                    val text = TextView(parent.context).apply {
                        id = android.R.id.text1
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        textSize = 16f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }
                    val editBtn = ImageButton(parent.context).apply {
                        id = android.R.id.edit
                        setImageResource(android.R.drawable.ic_menu_edit)
                        setBackgroundResource(android.R.color.transparent)
                        contentDescription = "Rename"
                    }
                    val deleteBtn = ImageButton(parent.context).apply {
                        id = android.R.id.button1
                        setImageResource(android.R.drawable.ic_delete)
                        setBackgroundResource(android.R.color.transparent)
                        contentDescription = "Delete"
                    }
                    layout.addView(chevron)
                    layout.addView(icon)
                    layout.addView(text)
                    layout.addView(editBtn)
                    layout.addView(deleteBtn)
                    FolderViewHolder(layout)
                }
                else -> {
                    val layout = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setPadding(48, 24, 48, 24)
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val text = TextView(parent.context).apply {
                        id = android.R.id.text1
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        textSize = 16f
                    }
                    val editBtn = ImageButton(parent.context).apply {
                        id = android.R.id.edit
                        setImageResource(android.R.drawable.ic_menu_edit)
                        setBackgroundResource(android.R.color.transparent)
                        contentDescription = "Rename"
                    }
                    val deleteBtn = ImageButton(parent.context).apply {
                        id = android.R.id.button1
                        setImageResource(android.R.drawable.ic_delete)
                        setBackgroundResource(android.R.color.transparent)
                        contentDescription = "Delete"
                    }
                    layout.addView(text)
                    layout.addView(editBtn)
                    layout.addView(deleteBtn)
                    EntryViewHolder(layout)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = currentItems[position]) {
                is HomeItem.FolderItem -> {
                    val vh = holder as FolderViewHolder
                    val density = vh.itemView.context.resources.displayMetrics.density
                    val indentPx = (item.depth * 32 * density).toInt()
                    vh.itemView.setPadding(48 + indentPx, 24, 48, 24)
                    vh.chevronView.text = if (item.isExpanded) "\u25BC" else "\u25B6"
                    vh.textView.text = item.folder.name
                    vh.itemView.setOnClickListener { onFolderClick(item.folder) }
                    vh.editButton.setOnClickListener { onFolderRename(item.folder) }
                    vh.deleteButton.setOnClickListener { onFolderDelete(item.folder) }
                }
                is HomeItem.EntryItem -> {
                    val vh = holder as EntryViewHolder
                    val density = vh.itemView.context.resources.displayMetrics.density
                    val indentPx = (item.depth * 32 * density).toInt()
                    vh.itemView.setPadding(48 + indentPx, 24, 48, 24)
                    vh.textView.text = item.entry.name
                    vh.itemView.setOnClickListener { onEntryClick(item.entry) }
                    vh.editButton.setOnClickListener { onEntryRename(item.entry) }
                    vh.deleteButton.setOnClickListener { onEntryDelete(item.entry) }
                }
            }
        }

        override fun getItemCount(): Int = currentItems.size
    }
}
