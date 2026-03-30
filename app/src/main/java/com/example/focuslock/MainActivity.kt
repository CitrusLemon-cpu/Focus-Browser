package com.example.focuslock

import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.focuslock.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var desktopMode = false
    private var currentFolderId: String? = null
    private var folderStack: MutableList<String?> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.settings.domStorageEnabled = true

        binding.webView.settings.safeBrowsingEnabled = true
        binding.webView.settings.allowFileAccess = false
        binding.webView.settings.allowContentAccess = false
        binding.webView.settings.setGeolocationEnabled(false)
        binding.webView.settings.databaseEnabled = false

        val prefs = getSharedPreferences("focus_lock_prefs", Context.MODE_PRIVATE)
        desktopMode = prefs.getBoolean("desktop_mode", false)
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

        binding.btnFolderBack.setOnClickListener {
            currentFolderId = folderStack.removeLastOrNull()
            refreshHomeList()
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
        currentFolderId = null
        folderStack.clear()
        refreshHomeList()
    }

    private fun showWebView() {
        binding.homeScreen.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.fab.visibility = View.GONE
        binding.fabNewFolder.visibility = View.GONE
    }

    private fun refreshHomeList() {
        if (currentFolderId != null) {
            val folder = WhitelistManager.getFolders(this).find { it.id == currentFolderId }
            binding.folderHeader.visibility = View.VISIBLE
            binding.folderTitle.text = folder?.name ?: "Unknown"
        } else {
            binding.folderHeader.visibility = View.GONE
        }

        val subfolders = WhitelistManager.getSubfolders(this, currentFolderId)
        val entries = WhitelistManager.getEntriesInFolder(this, currentFolderId)

        val items = mutableListOf<HomeItem>()
        subfolders.forEach { items.add(HomeItem.FolderItem(it)) }
        entries.forEach { items.add(HomeItem.EntryItem(it)) }

        if (items.isEmpty()) {
            binding.homeList.visibility = View.GONE
            binding.emptyMessage.visibility = View.VISIBLE
            if (currentFolderId != null) {
                binding.emptyMessage.text = "This folder is empty"
            } else {
                binding.emptyMessage.text = "No sites added yet.\nTap the \u2699 button to add sites in Settings."
            }
        } else {
            binding.homeList.visibility = View.VISIBLE
            binding.emptyMessage.visibility = View.GONE
            binding.homeList.layoutManager = LinearLayoutManager(this)
            binding.homeList.adapter = HomeAdapter(
                items,
                onFolderClick = { folder ->
                    folderStack.add(currentFolderId)
                    currentFolderId = folder.id
                    refreshHomeList()
                },
                onFolderLongPress = { folder -> showFolderSettingsDialog(folder) },
                onEntryClick = { entry ->
                    val url = if (entry.url.startsWith("http://") || entry.url.startsWith("https://")) {
                        entry.url
                    } else {
                        "https://${entry.url}"
                    }
                    showWebView()
                    binding.webView.loadUrl(url)
                },
                onEntryLongPress = { entry -> showEntrySettingsDialog(entry) }
            )
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
                    WhitelistManager.createFolder(this, name, currentFolderId)
                    refreshHomeList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildParentFolderChoices(excludeFolderId: String? = null): List<Pair<String?, String>> {
        val allFolders = WhitelistManager.getFolders(this)
        val excludeIds = mutableSetOf<String>()
        if (excludeFolderId != null) {
            excludeIds.add(excludeFolderId)
            fun collectChildren(parentId: String) {
                allFolders.filter { it.parentId == parentId }.forEach {
                    excludeIds.add(it.id)
                    collectChildren(it.id)
                }
            }
            collectChildren(excludeFolderId)
        }
        val result = mutableListOf<Pair<String?, String>>(null to "None (root)")
        fun addLevel(parentId: String?, indent: Int) {
            allFolders.filter { it.parentId == parentId && it.id !in excludeIds }.forEach { folder ->
                val prefix = "\u00A0\u00A0".repeat(indent)
                result.add(folder.id to "$prefix${folder.name}")
                addLevel(folder.id, indent + 1)
            }
        }
        addLevel(null, 0)
        return result
    }

    private fun showFolderSettingsDialog(folder: Folder) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val nameInput = EditText(this).apply {
            hint = "Folder name"
            setText(folder.name)
        }
        layout.addView(nameInput)

        val parentChoices = buildParentFolderChoices(excludeFolderId = folder.id)
        val parentNames = parentChoices.map { it.second }
        val currentParentIndex = parentChoices.indexOfFirst { it.first == folder.parentId }.coerceAtLeast(0)

        val parentLabel = TextView(this).apply {
            text = "Parent folder"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 24, 0, 4)
        }
        layout.addView(parentLabel)

        val parentSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, parentNames)
            setSelection(currentParentIndex)
        }
        layout.addView(parentSpinner)

        AlertDialog.Builder(this)
            .setTitle("Folder Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim()
                if (newName.isNotEmpty()) {
                    WhitelistManager.renameFolder(this, folder.id, newName)
                }
                val selectedParentId = parentChoices[parentSpinner.selectedItemPosition].first
                if (selectedParentId != folder.parentId) {
                    WhitelistManager.moveFolderToParent(this, folder.id, selectedParentId)
                }
                refreshHomeList()
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
                showDeleteFolderDialog(folder)
            }
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
                if (currentFolderId == folder.id) {
                    currentFolderId = folderStack.removeLastOrNull()
                }
                WhitelistManager.deleteFolder(this, folder.id)
                refreshHomeList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEntrySettingsDialog(entry: WhitelistEntry) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val nameInput = EditText(this).apply {
            hint = "Display name"
            setText(entry.name)
        }
        layout.addView(nameInput)

        val descInput = EditText(this).apply {
            hint = "Description"
            setText(entry.description)
            minLines = 2
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        layout.addView(descInput)

        AlertDialog.Builder(this)
            .setTitle("Site Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim()
                val newDesc = descInput.text.toString().trim()
                if (newName.isNotEmpty()) {
                    WhitelistManager.updateEntry(this, entry.url, entry.url, newName, newDesc)
                    refreshHomeList()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete") { _, _ ->
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
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.webView.visibility == View.VISIBLE) {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                showHome()
            }
        } else if (currentFolderId != null) {
            currentFolderId = folderStack.removeLastOrNull()
            refreshHomeList()
        } else {
            super.onBackPressed()
        }
    }

    private sealed class HomeItem {
        data class FolderItem(val folder: Folder) : HomeItem()
        data class EntryItem(val entry: WhitelistEntry) : HomeItem()
    }

    private class HomeAdapter(
        private val items: List<HomeItem>,
        private val onFolderClick: (Folder) -> Unit,
        private val onFolderLongPress: (Folder) -> Unit,
        private val onEntryClick: (WhitelistEntry) -> Unit,
        private val onEntryLongPress: (WhitelistEntry) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val VIEW_TYPE_FOLDER = 0
            private const val VIEW_TYPE_ENTRY = 1
        }

        class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconView: ImageView = view.findViewById(android.R.id.icon)
            val textView: TextView = view.findViewById(android.R.id.text1)
        }

        class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(android.R.id.text1)
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
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
                    layout.addView(icon)
                    layout.addView(text)
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
                    layout.addView(text)
                    EntryViewHolder(layout)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is HomeItem.FolderItem -> {
                    val vh = holder as FolderViewHolder
                    vh.textView.text = item.folder.name
                    vh.itemView.setOnClickListener { onFolderClick(item.folder) }
                    vh.itemView.setOnLongClickListener {
                        onFolderLongPress(item.folder)
                        true
                    }
                }
                is HomeItem.EntryItem -> {
                    val vh = holder as EntryViewHolder
                    vh.textView.text = item.entry.name
                    vh.itemView.setOnClickListener { onEntryClick(item.entry) }
                    vh.itemView.setOnLongClickListener {
                        onEntryLongPress(item.entry)
                        true
                    }
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
