package com.example.focuslock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.focuslock.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var desktopMode = false
    private var youtubeFocusMode = false
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val folderStack = mutableListOf<String?>(null)
    private val currentFolderId: String? get() = folderStack.last()
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private var activeFilterTag: String? = null
    private var isSearchMode = false

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

        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.homeScreen.visibility == View.VISIBLE && !isSearchMode) {
                enterSearchMode()
            }
        }

        binding.urlBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isSearchMode) {
                    updateSearchResults(s?.toString() ?: "")
                }
            }
        })

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
        binding.urlBar.clearFocus()
        folderStack.clear()
        folderStack.add(null)
        activeFilterTag = null
        isSearchMode = false
        binding.searchResultsList.visibility = View.GONE
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
        if (activeFilterTag != null) {
            val matchingEntries = WhitelistManager.getEntriesByTag(this, activeFilterTag!!)
            for (entry in matchingEntries) {
                items.add(HomeItem.EntryItem(entry))
            }
        } else {
            val subfolders = WhitelistManager.getSubfolders(this, currentFolderId)
            for (folder in subfolders) {
                items.add(HomeItem.FolderItem(folder))
            }
            val entries = WhitelistManager.getEntriesInFolder(this, currentFolderId)
            for (entry in entries) {
                items.add(HomeItem.EntryItem(entry))
            }
        }
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
                    folderStack.add(folder.id)
                    refreshHomeList()
                },
                onFolderLongPress = { folder -> showFolderMetadataDialog(folder) },
                onFolderDelete = { folder -> showDeleteFolderDialog(folder) },
                onEntryClick = { entry ->
                    val url = if (entry.url.startsWith("http://") || entry.url.startsWith("https://")) entry.url
                    else "https://${entry.url}"
                    showWebView()
                    binding.webView.loadUrl(url)
                },
                onEntryLongPress = { entry -> showEntryMetadataDialog(entry) },
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
        }

        if (activeFilterTag != null) {
            binding.breadcrumbScroll.visibility = View.GONE
            binding.fabNewFolder.visibility = View.GONE
        } else {
            binding.fabNewFolder.visibility = View.VISIBLE
            updateBreadcrumb()
        }
    }

    private fun enterSearchMode() {
        isSearchMode = true
        binding.homeList.visibility = View.GONE
        binding.emptyMessage.visibility = View.GONE
        binding.breadcrumbScroll.visibility = View.GONE
        binding.fab.visibility = View.GONE
        binding.fabNewFolder.visibility = View.GONE
        binding.searchResultsList.visibility = View.VISIBLE
        if (binding.searchResultsList.layoutManager == null) {
            binding.searchResultsList.layoutManager = LinearLayoutManager(this)
        }
        updateSearchResults("")
    }

    private fun exitSearchMode() {
        isSearchMode = false
        binding.searchResultsList.visibility = View.GONE
        binding.urlBar.setText("")
        binding.urlBar.clearFocus()
        hideKeyboard()
        binding.fab.visibility = View.VISIBLE
        binding.fabNewFolder.visibility = View.VISIBLE
        refreshHomeList()
    }

    private fun exitSearchModeAndRefresh() {
        isSearchMode = false
        binding.searchResultsList.visibility = View.GONE
        binding.urlBar.setText("")
        binding.urlBar.clearFocus()
        hideKeyboard()
        binding.fab.visibility = View.VISIBLE
        binding.fabNewFolder.visibility = View.VISIBLE
        refreshHomeList()
    }

    private fun navigateToFolder(folder: Folder) {
        isSearchMode = false
        binding.searchResultsList.visibility = View.GONE
        binding.urlBar.setText("")
        binding.urlBar.clearFocus()
        hideKeyboard()
        activeFilterTag = null

        val allFolders = WhitelistManager.getFolders(this)
        val path = mutableListOf<String>()
        var currentId: String? = folder.id
        while (currentId != null) {
            path.add(0, currentId)
            currentId = allFolders.find { it.id == currentId }?.parentId
        }

        folderStack.clear()
        folderStack.add(null)
        for (id in path) {
            folderStack.add(id)
        }
        refreshHomeList()
    }

    private fun updateSearchResults(query: String) {
        val items = mutableListOf<SearchItem>()
        val trimmedQuery = query.trim().lowercase()

        if (trimmedQuery.isEmpty()) {
            val allTags = WhitelistManager.getAllTags(this).sorted()
            if (allTags.isNotEmpty()) {
                items.add(SearchItem.SectionHeader("Tags"))
                for (tag in allTags) {
                    items.add(SearchItem.TagResult(tag))
                }
            }
        } else {
            val allTags = WhitelistManager.getAllTags(this).sorted()
            val matchingTags = allTags.filter { it.lowercase().contains(trimmedQuery) }
            if (matchingTags.isNotEmpty()) {
                items.add(SearchItem.SectionHeader("Tags"))
                for (tag in matchingTags) {
                    items.add(SearchItem.TagResult(tag))
                }
            }

            val allFolders = WhitelistManager.getFolders(this)
            val matchingFolders = allFolders.filter {
                it.name.lowercase().contains(trimmedQuery)
            }.sortedBy { it.name.lowercase() }
            if (matchingFolders.isNotEmpty()) {
                items.add(SearchItem.SectionHeader("Folders"))
                for (folder in matchingFolders) {
                    items.add(SearchItem.FolderResult(folder))
                }
            }

            val allEntries = WhitelistManager.getWhitelist(this)
            val matchingEntries = allEntries.filter {
                it.name.lowercase().contains(trimmedQuery) ||
                it.url.lowercase().contains(trimmedQuery)
            }.sortedBy { it.name.lowercase() }
            if (matchingEntries.isNotEmpty()) {
                items.add(SearchItem.SectionHeader("Sites"))
                for (entry in matchingEntries) {
                    items.add(SearchItem.EntryResult(entry))
                }
            }
        }

        val adapter = SearchAdapter(
            items,
            onTagClick = { tag ->
                activeFilterTag = tag
                exitSearchModeAndRefresh()
            },
            onFolderClick = { folder ->
                navigateToFolder(folder)
            },
            onEntryClick = { entry ->
                val url = if (entry.url.startsWith("http://") || entry.url.startsWith("https://")) entry.url
                else "https://${entry.url}"
                isSearchMode = false
                binding.searchResultsList.visibility = View.GONE
                binding.urlBar.clearFocus()
                hideKeyboard()
                showWebView()
                binding.webView.loadUrl(url)
            }
        )
        binding.searchResultsList.adapter = adapter
    }

    private fun updateBreadcrumb() {
        val container = binding.breadcrumbContainer
        container.removeAllViews()

        if (folderStack.size <= 1) {
            binding.breadcrumbScroll.visibility = View.GONE
            return
        }

        binding.breadcrumbScroll.visibility = View.VISIBLE
        val allFolders = WhitelistManager.getFolders(this)

        val homeText = TextView(this).apply {
            text = "Home"
            textSize = 14f
            setTextColor(getColor(com.google.android.material.R.color.design_default_color_primary))
            setOnClickListener {
                folderStack.clear()
                folderStack.add(null)
                refreshHomeList()
            }
        }
        container.addView(homeText)

        for (i in 1 until folderStack.size) {
            val sep = TextView(this).apply {
                text = " \u203A "
                textSize = 14f
            }
            container.addView(sep)

            val folderId = folderStack[i]!!
            val folder = allFolders.find { it.id == folderId }
            val isLast = (i == folderStack.size - 1)

            val label = TextView(this).apply {
                text = folder?.name ?: "\u2026"
                textSize = 14f
                if (isLast) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                } else {
                    setTextColor(getColor(com.google.android.material.R.color.design_default_color_primary))
                    setOnClickListener {
                        while (folderStack.size > i + 1) {
                            folderStack.removeAt(folderStack.size - 1)
                        }
                        refreshHomeList()
                    }
                }
            }
            container.addView(label)
        }

        binding.breadcrumbScroll.post {
            binding.breadcrumbScroll.fullScroll(android.widget.HorizontalScrollView.FOCUS_RIGHT)
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

    private fun showFolderMetadataDialog(folder: Folder) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val nameInput = EditText(this).apply {
            hint = "Folder name"
            setText(folder.name)
        }
        layout.addView(nameInput)

        AlertDialog.Builder(this)
            .setTitle("Edit Folder")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim()
                if (newName.isNotEmpty()) {
                    WhitelistManager.renameFolder(this, folder.id, newName)
                    refreshHomeList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEntryMetadataDialog(entry: WhitelistEntry) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val nameLabel = TextView(this).apply {
            text = "Display Name"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
        }
        val nameInput = EditText(this).apply {
            setText(entry.name)
            hint = "Display name"
        }
        layout.addView(nameLabel)
        layout.addView(nameInput)

        val tagsLabel = TextView(this).apply {
            text = "Tags"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 24, 0, 8)
        }
        layout.addView(tagsLabel)

        val chipScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val chipContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        chipScroll.addView(chipContainer)
        layout.addView(chipScroll)

        val currentTags = entry.tags.toMutableList()
        val dp = resources.displayMetrics.density

        fun rebuildChips() {
            chipContainer.removeAllViews()
            for (tag in currentTags) {
                val chip = TextView(this).apply {
                    text = "$tag  \u2715"
                    textSize = 13f
                    setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                    val bg = GradientDrawable().apply {
                        cornerRadius = 16 * dp
                        setColor(0x1F000000)
                    }
                    background = bg
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.marginEnd = (8 * dp).toInt()
                    layoutParams = lp
                    setOnClickListener {
                        currentTags.remove(tag)
                        rebuildChips()
                    }
                }
                chipContainer.addView(chip)
            }
        }
        rebuildChips()

        val addTagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
        }
        val tagInput = EditText(this).apply {
            hint = "Add tag..."
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val addBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Add"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun addTag() {
            val tag = tagInput.text.toString().trim().lowercase()
            if (tag.isNotEmpty() && tag !in currentTags) {
                currentTags.add(tag)
                tagInput.text.clear()
                rebuildChips()
            }
        }

        addBtn.setOnClickListener { addTag() }
        tagInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                addTag()
                true
            } else false
        }

        addTagRow.addView(tagInput)
        addTagRow.addView(addBtn)
        layout.addView(addTagRow)

        val allTags = WhitelistManager.getAllTags(this)
        val suggestions = allTags.filter { it !in currentTags }
        if (suggestions.isNotEmpty()) {
            val suggestLabel = TextView(this).apply {
                text = "Existing tags"
                textSize = 11f
                setTextColor(android.graphics.Color.GRAY)
                setPadding(0, 16, 0, 4)
            }
            layout.addView(suggestLabel)

            val suggestScroll = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
            }
            val suggestContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (tag in suggestions) {
                val chip = TextView(this).apply {
                    text = "+ $tag"
                    textSize = 12f
                    setPadding((12 * dp).toInt(), (4 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
                    val bg = GradientDrawable().apply {
                        cornerRadius = 16 * dp
                        setColor(0x0F000000)
                    }
                    background = bg
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.marginEnd = (8 * dp).toInt()
                    layoutParams = lp
                    setOnClickListener {
                        if (tag !in currentTags) {
                            currentTags.add(tag)
                            rebuildChips()
                            (parent as? LinearLayout)?.removeView(this)
                        }
                    }
                }
                suggestContainer.addView(chip)
            }
            suggestScroll.addView(suggestContainer)
            layout.addView(suggestScroll)
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(layout)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Site")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim()
                if (newName.isNotEmpty()) {
                    WhitelistManager.updateEntryName(this, entry.url, newName)
                }
                WhitelistManager.setEntryTags(this, entry.url, currentTags)
                refreshHomeList()
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
                WhitelistManager.deleteFolder(this, folder.id)
                val remainingFolders = WhitelistManager.getFolders(this@MainActivity)
                val remainingIds = remainingFolders.map { it.id }.toSet()
                folderStack.removeAll { it != null && it !in remainingIds }
                if (folderStack.isEmpty()) folderStack.add(null)
                refreshHomeList()
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
        val js = "(function() { var style = document.createElement('style'); style.id = 'yt-focus-mode'; style.textContent = \"$css\"; if (!document.getElementById('yt-focus-mode')) { document.head.appendChild(style); } })()"
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
        } else if (binding.homeScreen.visibility == View.VISIBLE) {
            if (isSearchMode) {
                exitSearchMode()
            } else if (activeFilterTag != null) {
                activeFilterTag = null
                refreshHomeList()
            } else if (folderStack.size > 1) {
                folderStack.removeAt(folderStack.size - 1)
                refreshHomeList()
            } else {
                super.onBackPressed()
            }
        } else {
            super.onBackPressed()
        }
    }

    private sealed class HomeItem {
        data class FolderItem(val folder: Folder) : HomeItem()
        data class EntryItem(val entry: WhitelistEntry) : HomeItem()
    }

    private class HomeAdapter(
        items: List<HomeItem>,
        private val onFolderClick: (Folder) -> Unit,
        private val onFolderLongPress: (Folder) -> Unit,
        private val onFolderDelete: (Folder) -> Unit,
        private val onEntryClick: (WhitelistEntry) -> Unit,
        private val onEntryLongPress: (WhitelistEntry) -> Unit,
        private val onEntryDelete: (WhitelistEntry) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val currentItems: MutableList<HomeItem> = items.toMutableList()

        companion object {
            private const val VIEW_TYPE_FOLDER = 0
            private const val VIEW_TYPE_ENTRY = 1
        }

        class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconView: ImageView = view.findViewById(android.R.id.icon)
            val textView: TextView = view.findViewById(android.R.id.text1)
            val deleteButton: ImageButton = view.findViewById(android.R.id.button1)
            val arrowView: TextView = view.findViewById(android.R.id.summary)
        }

        class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(android.R.id.text1)
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
                    val deleteBtn = ImageButton(parent.context).apply {
                        id = android.R.id.button1
                        setImageResource(android.R.drawable.ic_delete)
                        setBackgroundResource(android.R.color.transparent)
                        contentDescription = "Delete"
                    }
                    val arrow = TextView(parent.context).apply {
                        id = android.R.id.summary
                        setText("\u203A")
                        textSize = 18f
                        val size = (24 * parent.context.resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    layout.addView(icon)
                    layout.addView(text)
                    layout.addView(deleteBtn)
                    layout.addView(arrow)
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
                    val deleteBtn = ImageButton(parent.context).apply {
                        id = android.R.id.button1
                        setImageResource(android.R.drawable.ic_delete)
                        setBackgroundResource(android.R.color.transparent)
                        contentDescription = "Delete"
                    }
                    layout.addView(text)
                    layout.addView(deleteBtn)
                    EntryViewHolder(layout)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = currentItems[position]) {
                is HomeItem.FolderItem -> {
                    val vh = holder as FolderViewHolder
                    vh.textView.text = item.folder.name
                    vh.itemView.setOnClickListener { onFolderClick(item.folder) }
                    vh.itemView.setOnLongClickListener {
                        onFolderLongPress(item.folder)
                        true
                    }
                    vh.deleteButton.setOnClickListener { onFolderDelete(item.folder) }
                }
                is HomeItem.EntryItem -> {
                    val vh = holder as EntryViewHolder
                    vh.textView.text = item.entry.name
                    vh.itemView.setOnClickListener { onEntryClick(item.entry) }
                    vh.itemView.setOnLongClickListener {
                        onEntryLongPress(item.entry)
                        true
                    }
                    vh.deleteButton.setOnClickListener { onEntryDelete(item.entry) }
                }
            }
        }

        override fun getItemCount(): Int = currentItems.size
    }

    private sealed class SearchItem {
        data class SectionHeader(val title: String) : SearchItem()
        data class TagResult(val tag: String) : SearchItem()
        data class FolderResult(val folder: Folder) : SearchItem()
        data class EntryResult(val entry: WhitelistEntry) : SearchItem()
    }

    private class SearchAdapter(
        private val items: List<SearchItem>,
        private val onTagClick: (String) -> Unit,
        private val onFolderClick: (Folder) -> Unit,
        private val onEntryClick: (WhitelistEntry) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_HEADER = 0
            private const val TYPE_TAG = 1
            private const val TYPE_FOLDER = 2
            private const val TYPE_ENTRY = 3
        }

        class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view as TextView
        }
        class TagViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(android.R.id.text1)
        }
        class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconView: ImageView = view.findViewById(android.R.id.icon)
            val textView: TextView = view.findViewById(android.R.id.text1)
        }
        class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameView: TextView = view.findViewById(android.R.id.text1)
            val urlView: TextView = view.findViewById(android.R.id.text2)
        }

        override fun getItemViewType(position: Int) = when (items[position]) {
            is SearchItem.SectionHeader -> TYPE_HEADER
            is SearchItem.TagResult -> TYPE_TAG
            is SearchItem.FolderResult -> TYPE_FOLDER
            is SearchItem.EntryResult -> TYPE_ENTRY
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val dp = ctx.resources.displayMetrics.density
            return when (viewType) {
                TYPE_HEADER -> {
                    val tv = TextView(ctx).apply {
                        textSize = 12f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(android.graphics.Color.GRAY)
                        setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    HeaderViewHolder(tv)
                }
                TYPE_TAG -> {
                    val layout = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setBackgroundResource(android.R.attr.selectableItemBackground.let {
                            val outValue = android.util.TypedValue()
                            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                            outValue.resourceId
                        })
                    }
                    val icon = TextView(ctx).apply {
                        text = "#"
                        textSize = 18f
                        setTextColor(android.graphics.Color.GRAY)
                        val size = (32 * dp).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            marginEnd = (8 * dp).toInt()
                        }
                        gravity = android.view.Gravity.CENTER
                    }
                    val text = TextView(ctx).apply {
                        id = android.R.id.text1
                        textSize = 16f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    layout.addView(icon)
                    layout.addView(text)
                    TagViewHolder(layout)
                }
                TYPE_FOLDER -> {
                    val layout = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setBackgroundResource(android.R.attr.selectableItemBackground.let {
                            val outValue = android.util.TypedValue()
                            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                            outValue.resourceId
                        })
                    }
                    val icon = ImageView(ctx).apply {
                        id = android.R.id.icon
                        setImageResource(android.R.drawable.ic_menu_agenda)
                        val size = (32 * dp).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            marginEnd = (8 * dp).toInt()
                        }
                    }
                    val text = TextView(ctx).apply {
                        id = android.R.id.text1
                        textSize = 16f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    layout.addView(icon)
                    layout.addView(text)
                    FolderViewHolder(layout)
                }
                else -> {
                    val layout = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setBackgroundResource(android.R.attr.selectableItemBackground.let {
                            val outValue = android.util.TypedValue()
                            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                            outValue.resourceId
                        })
                    }
                    val textContainer = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val nameText = TextView(ctx).apply {
                        id = android.R.id.text1
                        textSize = 16f
                    }
                    val urlText = TextView(ctx).apply {
                        id = android.R.id.text2
                        textSize = 12f
                        setTextColor(android.graphics.Color.GRAY)
                    }
                    textContainer.addView(nameText)
                    textContainer.addView(urlText)
                    layout.addView(textContainer)
                    EntryViewHolder(layout)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is SearchItem.SectionHeader -> {
                    (holder as HeaderViewHolder).textView.text = item.title.uppercase()
                }
                is SearchItem.TagResult -> {
                    val vh = holder as TagViewHolder
                    vh.textView.text = item.tag
                    vh.itemView.setOnClickListener { onTagClick(item.tag) }
                }
                is SearchItem.FolderResult -> {
                    val vh = holder as FolderViewHolder
                    vh.textView.text = item.folder.name
                    vh.itemView.setOnClickListener { onFolderClick(item.folder) }
                }
                is SearchItem.EntryResult -> {
                    val vh = holder as EntryViewHolder
                    vh.nameView.text = item.entry.name
                    vh.urlView.text = item.entry.url
                    vh.itemView.setOnClickListener { onEntryClick(item.entry) }
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
