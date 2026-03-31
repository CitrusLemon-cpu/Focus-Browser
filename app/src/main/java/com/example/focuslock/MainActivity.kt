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
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
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
    private var showHiddenItems = false
    private var currentEmbedVideoId: String? = null
    private var invidiousRedirectEnabled = false
    private var invidiousInstance = "yewtu.be"
    private var hideFinishedVideos = false
    private var showTags = false
    private var showVideoProgress = false
    private var showConsumedToday = false

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
        binding.webView.settings.databaseEnabled = true
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        val prefs = getSharedPreferences("focus_lock_prefs", Context.MODE_PRIVATE)
        desktopMode = prefs.getBoolean("desktop_mode", false)
        youtubeFocusMode = prefs.getBoolean("youtube_focus_mode", false)
        invidiousRedirectEnabled = prefs.getBoolean("invidious_redirect_enabled", false)
        invidiousInstance = prefs.getString("invidious_instance", "yewtu.be") ?: "yewtu.be"
        hideFinishedVideos = prefs.getBoolean("hide_finished_videos", false)
        showTags = prefs.getBoolean("show_tags", false)
        showVideoProgress = prefs.getBoolean("show_video_progress", false)
        showConsumedToday = prefs.getBoolean("show_consumed_today", false)
        applyDesktopMode()

        binding.webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun updateProgress(videoId: String, currentTime: Double, duration: Double) {
                VideoProgressManager.updateProgress(this@MainActivity, videoId, currentTime, duration)
            }
        }, "FocusBridge")

        binding.switchShowHidden.isChecked = false
        binding.switchShowHidden.setOnCheckedChangeListener { _, isChecked ->
            showHiddenItems = isChecked
            if (binding.homeScreen.visibility == View.VISIBLE) {
                if (isSearchMode) {
                    updateSearchResults(binding.urlBar.text.toString())
                } else {
                    refreshHomeList()
                }
            }
        }

        binding.switchYoutubeEmbed.isChecked = prefs.getBoolean("youtube_focus_mode", false)
        binding.switchYoutubeEmbed.setOnCheckedChangeListener { _, isChecked ->
            youtubeFocusMode = isChecked
            prefs.edit().putBoolean("youtube_focus_mode", isChecked).apply()

            if (isChecked && invidiousRedirectEnabled) {
                invidiousRedirectEnabled = false
                prefs.edit().putBoolean("invidious_redirect_enabled", false).apply()
                binding.switchInvidiousRedirect.isChecked = false
                binding.invidiousInstanceContainer.visibility = View.GONE
            }

        }

        binding.switchInvidiousRedirect.isChecked = invidiousRedirectEnabled
        binding.invidiousInstanceContainer.visibility = if (invidiousRedirectEnabled) View.VISIBLE else View.GONE

        if (invidiousInstance == "invidious.nadeko.net") {
            binding.radioNadeko.isChecked = true
        } else {
            binding.radioYewtu.isChecked = true
        }

        binding.switchInvidiousRedirect.setOnCheckedChangeListener { _, isChecked ->
            invidiousRedirectEnabled = isChecked
            prefs.edit().putBoolean("invidious_redirect_enabled", isChecked).apply()

            binding.invidiousInstanceContainer.visibility = if (isChecked) View.VISIBLE else View.GONE

            if (isChecked && youtubeFocusMode) {
                youtubeFocusMode = false
                prefs.edit().putBoolean("youtube_focus_mode", false).apply()
                binding.switchYoutubeEmbed.isChecked = false
            }

        }

        binding.invidiousInstanceGroup.setOnCheckedChangeListener { _, checkedId ->
            invidiousInstance = if (checkedId == binding.radioNadeko.id) "invidious.nadeko.net" else "yewtu.be"
            prefs.edit().putString("invidious_instance", invidiousInstance).apply()
        }

        binding.switchHideFinished.isChecked = prefs.getBoolean("hide_finished_videos", false)
        binding.switchHideFinished.setOnCheckedChangeListener { _, isChecked ->
            hideFinishedVideos = isChecked
            prefs.edit().putBoolean("hide_finished_videos", isChecked).apply()
            if (binding.homeScreen.visibility == View.VISIBLE) refreshHomeList()
        }

        binding.btnResetProgress.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset Video Progress")
                .setMessage("Reset all video watch progress? This cannot be undone.")
                .setPositiveButton("Reset") { _, _ ->
                    VideoProgressManager.resetAllProgress(this)
                    android.widget.Toast.makeText(this, "Video progress reset", android.widget.Toast.LENGTH_SHORT).show()
                    if (binding.homeScreen.visibility == View.VISIBLE) refreshHomeList()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.switchShowTags.isChecked = showTags
        binding.switchShowTags.setOnCheckedChangeListener { _, isChecked ->
            showTags = isChecked
            prefs.edit().putBoolean("show_tags", isChecked).apply()
            if (binding.homeScreen.visibility == View.VISIBLE) refreshHomeList()
        }

        binding.switchShowVideoProgress.isChecked = showVideoProgress
        binding.switchShowVideoProgress.setOnCheckedChangeListener { _, isChecked ->
            showVideoProgress = isChecked
            prefs.edit().putBoolean("show_video_progress", isChecked).apply()
            if (binding.homeScreen.visibility == View.VISIBLE) refreshHomeList()
        }

        binding.switchShowConsumedToday.isChecked = showConsumedToday
        binding.switchShowConsumedToday.setOnCheckedChangeListener { _, isChecked ->
            showConsumedToday = isChecked
            prefs.edit().putBoolean("show_consumed_today", isChecked).apply()
            if (binding.homeScreen.visibility == View.VISIBLE) refreshHomeList()
        }

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

                if (isYouTubeShorts(urlString)) {
                    view?.post {
                        showHome()
                        showShortsBlockedDialog()
                    }
                    return true
                }

                if (youtubeFocusMode && isYouTubeWatchPage(urlString)) {
                    val videoId = extractYouTubeVideoId(urlString)
                    if (videoId != null && videoId != currentEmbedVideoId && WhitelistManager.isUrlAllowed(this@MainActivity, urlString)) {
                        view?.post { loadYouTubeEmbed(videoId, urlString) }
                        return true
                    }
                }

                currentEmbedVideoId = null

                if (invidiousRedirectEnabled && isYouTubeUrl(urlString)) {
                    val rewritten = rewriteYouTubeToInvidious(urlString)
                    view?.post { view.loadUrl(rewritten) }
                    return true
                }

                val currentUA = view?.settings?.userAgentString ?: ""
                val expectedDesktop = desktopMode || requiresAutoDesktop(urlString)
                val currentIsDesktop = currentUA.contains("Windows NT")
                if (expectedDesktop != currentIsDesktop) {
                    applyUserAgentForUrl(urlString)
                    view?.post { view.loadUrl(urlString) }
                    return true
                }

                val urlToCheck = if (invidiousRedirectEnabled && isInvidiousUrl(urlString)) {
                    invidiousToYouTubeUrl(urlString)
                } else {
                    urlString
                }
                if (WhitelistManager.isUrlAllowed(this@MainActivity, urlToCheck)) {
                    val (inBlocked, blockedFolderName) = WhitelistManager.isUrlInBlockedFolder(this@MainActivity, urlToCheck)
                    return if (inBlocked) {
                        view?.post {
                            showHome()
                            val remaining = blockedFolderName?.let { name ->
                                val folders = WhitelistManager.getFolders(this@MainActivity)
                                val folder = folders.find { it.name == name }
                                folder?.let { WhitelistManager.getFolderBlockTimeRemaining(this@MainActivity, it.id) } ?: 0L
                            } ?: 0L
                            showFolderBlockedDialog(urlString, blockedFolderName, remaining)
                        }
                        true
                    } else if (WhitelistManager.isUrlBlockedByLockIn(this@MainActivity, urlToCheck)) {
                        view?.post {
                            showHome()
                            val remaining = WhitelistManager.getLockInBlockTimeRemaining(this@MainActivity, urlToCheck)
                            showLockInBlockedDialog(urlString, remaining)
                        }
                        true
                    } else {
                        false
                    }
                } else {
                    view?.post {
                        showHome()
                        showBlockedDialog(urlString)
                    }
                    return true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (currentEmbedVideoId != null) return

                if (url == "about:blank") {
                    view?.clearHistory()
                } else {
                    url?.let {
                        binding.urlBar.setText(it)
                        updateDesktopToggleVisibility(it)
                    }
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (currentEmbedVideoId != null) return

                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    updateDesktopToggleVisibility(url)
                    if (isYouTubeShorts(url)) {
                        view?.post {
                            showHome()
                            showShortsBlockedDialog()
                        }
                        return
                    }
                    val urlToCheck = if (invidiousRedirectEnabled && isInvidiousUrl(url)) {
                        invidiousToYouTubeUrl(url)
                    } else {
                        url
                    }
                    if (!WhitelistManager.isUrlAllowed(this@MainActivity, urlToCheck)) {
                        view?.post {
                            showHome()
                            showBlockedDialog(url)
                        }
                        return
                    }
                    val (inBlocked, blockedFolderName) = WhitelistManager.isUrlInBlockedFolder(this@MainActivity, urlToCheck)
                    if (inBlocked) {
                        view?.post {
                            showHome()
                            val remaining = blockedFolderName?.let { name ->
                                val folders = WhitelistManager.getFolders(this@MainActivity)
                                val folder = folders.find { it.name == name }
                                folder?.let { WhitelistManager.getFolderBlockTimeRemaining(this@MainActivity, it.id) } ?: 0L
                            } ?: 0L
                            showFolderBlockedDialog(url, blockedFolderName, remaining)
                        }
                        return
                    }
                    if (WhitelistManager.isUrlBlockedByLockIn(this@MainActivity, urlToCheck)) {
                        view?.post {
                            showHome()
                            val remaining = WhitelistManager.getLockInBlockTimeRemaining(this@MainActivity, urlToCheck)
                            showLockInBlockedDialog(url, remaining)
                        }
                        return
                    }
                    if (youtubeFocusMode && isYouTubeWatchPage(url)) {
                        val videoId = extractYouTubeVideoId(url)
                        if (videoId != null) {
                            view?.post { loadYouTubeEmbed(videoId, url) }
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

    private fun requiresAutoDesktop(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("instagram.com") ||
               (lower.contains("facebook.com") && lower.contains("/messages"))
    }

    private fun applyUserAgentForUrl(url: String) {
        val settings = binding.webView.settings
        val useDesktop = desktopMode || requiresAutoDesktop(url)
        settings.userAgentString = if (useDesktop) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        } else {
            WebSettings.getDefaultUserAgent(this)
        }
        settings.useWideViewPort = useDesktop
        settings.loadWithOverviewMode = useDesktop
    }

    private fun updateDesktopToggleVisibility(url: String?) {
        val locked = url != null && requiresAutoDesktop(url)
        binding.btnDesktopMode.visibility = if (locked) View.GONE else View.VISIBLE
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
        binding.switchYoutubeEmbed.isChecked = youtubeFocusMode
        invidiousRedirectEnabled = prefs.getBoolean("invidious_redirect_enabled", false)
        invidiousInstance = prefs.getString("invidious_instance", "yewtu.be") ?: "yewtu.be"
        hideFinishedVideos = prefs.getBoolean("hide_finished_videos", false)
        binding.switchInvidiousRedirect.isChecked = invidiousRedirectEnabled
        binding.invidiousInstanceContainer.visibility = if (invidiousRedirectEnabled) View.VISIBLE else View.GONE
        if (binding.homeScreen.visibility == View.VISIBLE) {
            refreshHomeList()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.webkit.CookieManager.getInstance().flush()
    }

    private fun navigateToInput(input: String) {
        val url = if (input.contains(".") && !input.contains(" ")) {
            if (input.startsWith("http://") || input.startsWith("https://")) input
            else "https://$input"
        } else {
            "https://www.google.com/search?q=${java.net.URLEncoder.encode(input, "UTF-8")}"
        }

        if (youtubeFocusMode && isYouTubeWatchPage(url)) {
            val videoId = extractYouTubeVideoId(url)
            if (videoId != null && WhitelistManager.isUrlAllowed(this@MainActivity, url)) {
                hideKeyboard()
                loadYouTubeEmbed(videoId, url)
                return
            }
        }

currentEmbedVideoId = null

        if (invidiousRedirectEnabled && isYouTubeUrl(url)) {
            val rewritten = rewriteYouTubeToInvidious(url)
            hideKeyboard()
            showWebView()
            applyUserAgentForUrl(url)
            binding.webView.loadUrl(rewritten)
            return
        }

        hideKeyboard()
        showWebView()
        applyUserAgentForUrl(url)
        binding.webView.loadUrl(url)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
    }

    private fun showHome() {
        currentEmbedVideoId = null
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
        updateDesktopToggleVisibility(null)
    }

    private fun showWebView() {
        binding.homeScreen.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.fab.visibility = View.GONE
        binding.fabNewFolder.visibility = View.GONE
    }

    private fun getAllEntriesInFolderRecursive(folderId: String?): List<WhitelistEntry> {
        val result = mutableListOf<WhitelistEntry>()
        result.addAll(WhitelistManager.getEntriesInFolder(this, folderId))
        val subfolders = WhitelistManager.getSubfolders(this, folderId)
        for (sub in subfolders) {
            result.addAll(getAllEntriesInFolderRecursive(sub.id))
        }
        return result
    }

    private fun buildItemList(): List<HomeItem> {
        val items = mutableListOf<HomeItem>()
        if (activeFilterTag != null) {
            val matchingEntries = WhitelistManager.getEntriesByTag(this, activeFilterTag!!)
            for (entry in matchingEntries) {
                if (!showHiddenItems && WhitelistManager.isEntryEffectivelyHidden(this, entry)) continue
                items.add(HomeItem.EntryItem(entry))
            }
        } else {
            val lockInAncestorId = if (currentFolderId != null)
                WhitelistManager.getEffectiveLockInFolderId(this, currentFolderId!!) else null
            val activeLockInSession = if (lockInAncestorId != null)
                WhitelistManager.getLockInSession(this, lockInAncestorId) else null

            if (activeLockInSession != null) {
                val lockedUrl = activeLockInSession.first!!
                val allEntries = getAllEntriesInFolderRecursive(lockInAncestorId)
                val lockedEntry = allEntries.find {
                    WhitelistManager.normalizeUrl(it.url) == WhitelistManager.normalizeUrl(lockedUrl)
                }
                if (lockedEntry != null) {
                    items.add(HomeItem.EntryItem(lockedEntry, isLockedOut = false))
                }
                if (showHiddenItems) {
                    val subfolders = WhitelistManager.getSubfolders(this, currentFolderId)
                    for (folder in subfolders) {
                        items.add(HomeItem.FolderItem(folder))
                    }
                    val entries = WhitelistManager.getEntriesInFolder(this, currentFolderId)
                    for (entry in entries) {
                        if (lockedEntry != null && entry.url == lockedEntry.url && entry.folderId == lockedEntry.folderId) continue
                        items.add(HomeItem.EntryItem(entry, isLockedOut = true))
                    }
                }
            } else {
                val subfolders = WhitelistManager.getSubfolders(this, currentFolderId)
                if (currentFolderId == null) {
                    val curated = subfolders.filter { it.isCurated }.sortedBy { it.sortOrder }
                    val regular = subfolders.filter { !it.isCurated }.sortedBy { it.sortOrder }
                    for (folder in curated) {
                        if (!showHiddenItems && folder.hidden) continue
                        items.add(HomeItem.FolderItem(folder))
                    }
                    for (folder in regular) {
                        if (!showHiddenItems && folder.hidden) continue
                        val effectiveId = WhitelistManager.getEffectiveLockInFolderId(this, folder.id)
                        val session = if (effectiveId != null) WhitelistManager.getLockInSession(this, effectiveId) else null
                        if (session != null) {
                            val lockedUrl = session.first!!
                            val allEntries = getAllEntriesInFolderRecursive(effectiveId)
                            val lockedEntry = allEntries.find {
                                WhitelistManager.normalizeUrl(it.url) == WhitelistManager.normalizeUrl(lockedUrl)
                            }
                            if (lockedEntry != null) {
                                items.add(HomeItem.EntryItem(lockedEntry))
                            }
                            if (showHiddenItems) {
                                items.add(HomeItem.FolderItem(folder))
                            }
                        } else {
                            items.add(HomeItem.FolderItem(folder))
                        }
                    }
                } else {
                    for (folder in subfolders) {
                        if (!showHiddenItems && folder.hidden) continue
                        items.add(HomeItem.FolderItem(folder))
                    }
                }
                val entries = WhitelistManager.getEntriesInFolder(this, currentFolderId)
                for (entry in entries) {
                    if (!showHiddenItems && entry.hidden) continue
                    items.add(HomeItem.EntryItem(entry))
                }
            }
        }

        val allProgress = VideoProgressManager.getAllProgress(this)

        if (hideFinishedVideos && !showHiddenItems) {
            items.removeAll { item ->
                if (item is HomeItem.EntryItem) {
                    val vid = VideoProgressManager.extractVideoId(item.entry.url)
                    vid != null && allProgress[vid]?.isFinished == true
                } else false
            }
        }

        val folders = items.filterIsInstance<HomeItem.FolderItem>()
        val entries = items.filterIsInstance<HomeItem.EntryItem>()

        val inProgress = mutableListOf<HomeItem.EntryItem>()
        val normal = mutableListOf<HomeItem.EntryItem>()
        val finished = mutableListOf<HomeItem.EntryItem>()

        for (entry in entries) {
            val vid = VideoProgressManager.extractVideoId(entry.entry.url)
            val progress = if (vid != null) allProgress[vid] else null
            when {
                progress != null && progress.isFinished -> finished.add(entry)
                progress != null && progress.isStarted -> inProgress.add(entry)
                else -> normal.add(entry)
            }
        }

        inProgress.sortByDescending { e ->
            val vid = VideoProgressManager.extractVideoId(e.entry.url)
            if (vid != null) allProgress[vid]?.lastWatched ?: 0L else 0L
        }

        return folders + inProgress + normal + finished
    }

    private fun wasVisitedToday(url: String): Boolean {
        val prefs = getSharedPreferences("focus_lock_prefs", Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        return prefs.getString("visited_today_$url", null) == today
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
                showTags = showTags,
                showVideoProgress = showVideoProgress,
                showConsumedToday = showConsumedToday,
                wasVisitedToday = { url -> wasVisitedToday(url) },
                onFolderClick = { folder ->
                    folderStack.add(folder.id)
                    refreshHomeList()
                },
                onFolderLongPress = { folder -> showFolderMetadataDialog(folder) },
                onFolderDelete = { folder -> showDeleteFolderDialog(folder) },
                onEntryClick = { entry ->
                    val url = if (entry.url.startsWith("http://") || entry.url.startsWith("https://")) entry.url
                    else "https://${entry.url}"

                    if (entry.sourceFolderId != null && WhitelistManager.isFolderEffectivelyBlocked(this, entry.sourceFolderId)) {
                        val allFolders = WhitelistManager.getFolders(this)
                        var currentId: String? = entry.sourceFolderId
                        var blockedFolderName: String? = null
                        var remaining = 0L
                        while (currentId != null) {
                            val f = allFolders.find { it.id == currentId } ?: break
                            if (f.blockedUntil != null && System.currentTimeMillis() < f.blockedUntil) {
                                blockedFolderName = f.name
                                remaining = WhitelistManager.getFolderBlockTimeRemaining(this, f.id)
                                break
                            }
                            currentId = f.parentId
                        }
                        showFolderBlockedDialog(url, blockedFolderName, remaining)
                        return@HomeAdapter
                    }

                    if (entry.folderId != null && WhitelistManager.isFolderEffectivelyBlocked(this, entry.folderId)) {
                        val allFolders = WhitelistManager.getFolders(this)
                        var currentId: String? = entry.folderId
                        var blockedFolderName: String? = null
                        var remaining = 0L
                        while (currentId != null) {
                            val f = allFolders.find { it.id == currentId } ?: break
                            if (f.blockedUntil != null && System.currentTimeMillis() < f.blockedUntil) {
                                blockedFolderName = f.name
                                remaining = WhitelistManager.getFolderBlockTimeRemaining(this, f.id)
                                break
                            }
                            currentId = f.parentId
                        }
                        showFolderBlockedDialog(url, blockedFolderName, remaining)
                        return@HomeAdapter
                    }

                    if (entry.folderId != null) {
                        val currentFolder = WhitelistManager.getFolders(this).find { it.id == entry.folderId }

                        if (currentFolder?.isCurated == true && currentFolder.ignoreLockInMode) {
                            // Exempt from lock-in — skip lock-in checks
                        } else if (currentFolder?.isCurated == true && !currentFolder.ignoreLockInMode) {
                            val originalFolderId = entry.sourceFolderId ?: entry.folderId
                            val effectiveLockInId = WhitelistManager.getEffectiveLockInFolderId(this, originalFolderId!!)
                            if (effectiveLockInId != null) {
                                val session = WhitelistManager.getLockInSession(this, effectiveLockInId)
                                if (session != null && WhitelistManager.normalizeUrl(entry.url) != WhitelistManager.normalizeUrl(session.first!!)) {
                                    val remaining = session.second - System.currentTimeMillis()
                                    showLockInBlockedDialog(url, remaining)
                                    return@HomeAdapter
                                }
                            }
                        } else {
                            val folderId = entry.folderId
                            val lockInFolderId = WhitelistManager.getEffectiveLockInFolderId(this, folderId) ?: folderId
                            val session = WhitelistManager.getLockInSession(this, lockInFolderId)
                            if (session != null) {
                                val lockedUrl = session.first
                                if (WhitelistManager.normalizeUrl(url) != WhitelistManager.normalizeUrl(lockedUrl!!)) {
                                    val remaining = session.second - System.currentTimeMillis()
                                    showLockInBlockedDialog(url, remaining)
                                    return@HomeAdapter
                                }
                            } else if (WhitelistManager.isLockInArmed(this, folderId)) {
                                val folder = WhitelistManager.getFolders(this).find { it.id == lockInFolderId }
                                if (folder?.lockInWarningEnabled == true) {
                                    showLockInWarningDialog(entry, url, lockInFolderId, folder.lockInDurationMinutes)
                                    return@HomeAdapter
                                } else {
                                    WhitelistManager.startLockInSession(this, lockInFolderId, entry.url)
                                    refreshHomeList()
                                }
                            }
                        }
                    }

                    showWebView()
                    val visitPrefs = getSharedPreferences("focus_lock_prefs", Context.MODE_PRIVATE)
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                    visitPrefs.edit().putString("visited_today_${entry.url}", today).apply()
                    binding.webView.loadUrl(url)
                },
                onEntryLongPress = { entry -> showEntryMetadataDialog(entry) },
                onEntryDelete = { entry ->
                    val entryFolder = if (entry.folderId != null) WhitelistManager.getFolders(this).find { it.id == entry.folderId } else null
                    if (entryFolder?.isCurated == true && entryFolder.preventEditWithoutPassword) {
                        showPasswordDialogThen {
                            AlertDialog.Builder(this)
                                .setTitle("Remove Entry")
                                .setMessage("Are you sure you want to remove ${entry.name}?")
                                .setPositiveButton("Remove") { _, _ ->
                                    WhitelistManager.removeEntryFromFolder(this, entry.url, entryFolder.id)
                                    refreshHomeList()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    } else if (entryFolder?.isCurated == true) {
                        AlertDialog.Builder(this)
                            .setTitle("Remove Entry")
                            .setMessage("Remove ${entry.name} from ${entryFolder.name}?")
                            .setPositiveButton("Remove") { _, _ ->
                                WhitelistManager.removeEntryFromFolder(this, entry.url, entryFolder.id)
                                refreshHomeList()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
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
            val allTags = if (showHiddenItems) {
                WhitelistManager.getAllTags(this).sorted()
            } else {
                WhitelistManager.getWhitelist(this)
                    .filter { !WhitelistManager.isEntryEffectivelyHidden(this, it) }
                    .flatMap { it.tags }
                    .toSet()
                    .sorted()
            }
            if (allTags.isNotEmpty()) {
                items.add(SearchItem.SectionHeader("Tags"))
                for (tag in allTags) {
                    items.add(SearchItem.TagResult(tag))
                }
            }
        } else {
            val allTags = if (showHiddenItems) {
                WhitelistManager.getAllTags(this).sorted()
            } else {
                WhitelistManager.getWhitelist(this)
                    .filter { !WhitelistManager.isEntryEffectivelyHidden(this, it) }
                    .flatMap { it.tags }
                    .toSet()
                    .sorted()
            }
            val matchingTags = allTags.filter { it.lowercase().contains(trimmedQuery) }
            if (matchingTags.isNotEmpty()) {
                items.add(SearchItem.SectionHeader("Tags"))
                for (tag in matchingTags) {
                    items.add(SearchItem.TagResult(tag))
                }
            }

            val allFolders = WhitelistManager.getFolders(this)
            val matchingFolders = allFolders.filter {
                it.name.lowercase().contains(trimmedQuery) &&
                (showHiddenItems || !WhitelistManager.isFolderEffectivelyHidden(this, it.id))
            }.sortedBy { it.name.lowercase() }
            if (matchingFolders.isNotEmpty()) {
                items.add(SearchItem.SectionHeader("Folders"))
                for (folder in matchingFolders) {
                    items.add(SearchItem.FolderResult(folder))
                }
            }

            val allEntries = WhitelistManager.getWhitelist(this)
            val matchingEntries = allEntries.filter {
                (it.name.lowercase().contains(trimmedQuery) ||
                it.url.lowercase().contains(trimmedQuery)) &&
                (showHiddenItems || !WhitelistManager.isEntryEffectivelyHidden(this, it))
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

        val hiddenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 0)
        }
        val hiddenLabel = TextView(this).apply {
            text = "Hidden"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hiddenSwitch = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            isChecked = folder.hidden
        }
        hiddenRow.addView(hiddenLabel)
        hiddenRow.addView(hiddenSwitch)
        layout.addView(hiddenRow)

        val effectiveLockInId = WhitelistManager.getEffectiveLockInFolderId(this, folder.id)
        if (effectiveLockInId != null) {
            val isInherited = effectiveLockInId != folder.id
            val lockInStatusLabel = TextView(this).apply {
                text = "Lock-in Mode"
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
                setPadding(0, 32, 0, 8)
            }
            layout.addView(lockInStatusLabel)

            val session = WhitelistManager.getLockInSession(this, effectiveLockInId)
            if (session != null) {
                val remaining = session.second - System.currentTimeMillis()
                val mins = (remaining / 60000).coerceAtLeast(1)
                val inheritedNote = if (isInherited) " (inherited from parent)" else ""
                val statusText = TextView(this).apply {
                    text = "\uD83D\uDD12 Active \u2014 locked for ${mins}m remaining$inheritedNote"
                    textSize = 14f
                    setPadding(0, 8, 0, 8)
                }
                layout.addView(statusText)
            } else {
                val inheritedNote = if (isInherited) " (inherited from parent)" else ""
                val statusText = TextView(this).apply {
                    text = "\uD83D\uDD13 Armed \u2014 waiting for first tap$inheritedNote"
                    textSize = 14f
                    setPadding(0, 8, 0, 8)
                }
                layout.addView(statusText)
            }

            val turnOffBtn = MaterialButton(this).apply {
                text = "Turn off Lock-in Mode"
                tag = "lockInTurnOff"
            }
            layout.addView(turnOffBtn)
        }

        val blockLabel = TextView(this).apply {
            text = "Block Folder"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 32, 0, 8)
        }
        layout.addView(blockLabel)

        if (WhitelistManager.isFolderBlocked(this, folder.id)) {
            val remaining = WhitelistManager.getFolderBlockTimeRemaining(this, folder.id)
            val hours = remaining / 3600000
            val minutes = (remaining % 3600000) / 60000
            val statusText = TextView(this).apply {
                text = "\uD83D\uDD12 Blocked \u2014 ${hours}h ${minutes}m remaining"
                textSize = 14f
                setPadding(0, 8, 0, 8)
            }
            layout.addView(statusText)

            val unblockBtn = MaterialButton(this).apply {
                text = "Unblock (requires password)"
                setOnClickListener {
                    showUnblockPasswordDialog(folder.id)
                }
            }
            layout.addView(unblockBtn)
        } else {
            val durationOptions = listOf(
                "30 minutes" to 30 * 60 * 1000L,
                "1 hour" to 60 * 60 * 1000L,
                "2 hours" to 2 * 60 * 60 * 1000L,
                "4 hours" to 4 * 60 * 60 * 1000L,
                "8 hours" to 8 * 60 * 60 * 1000L,
                "12 hours" to 12 * 60 * 60 * 1000L
            )
            var selectedDuration = durationOptions[0].second

            val radioGroup = RadioGroup(this).apply {
                orientation = RadioGroup.VERTICAL
            }
            for ((index, option) in durationOptions.withIndex()) {
                val rb = RadioButton(this).apply {
                    text = option.first
                    id = View.generateViewId()
                    if (index == 0) isChecked = true
                }
                radioGroup.addView(rb)
            }
            radioGroup.setOnCheckedChangeListener { group, checkedId ->
                for ((index, option) in durationOptions.withIndex()) {
                    if (group.getChildAt(index).id == checkedId) {
                        selectedDuration = option.second
                        break
                    }
                }
            }
            layout.addView(radioGroup)

            val blockBtn = MaterialButton(this)
            blockBtn.text = "Block"
            layout.addView(blockBtn)

            val dialog = AlertDialog.Builder(this)
                .setTitle("Edit Folder")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    val newName = nameInput.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        WhitelistManager.renameFolder(this, folder.id, newName)
                    }
                    WhitelistManager.setFolderHidden(this, folder.id, hiddenSwitch.isChecked)
                    refreshHomeList()
                }
                .setNegativeButton("Cancel", null)
                .show()

            blockBtn.setOnClickListener {
                WhitelistManager.blockFolder(this@MainActivity, folder.id, selectedDuration)
                dialog.dismiss()
                refreshHomeList()
                android.widget.Toast.makeText(this@MainActivity, "Folder blocked", android.widget.Toast.LENGTH_SHORT).show()
            }
            layout.findViewWithTag<MaterialButton>("lockInTurnOff")?.setOnClickListener {
                showDisableLockInPasswordDialog(effectiveLockInId ?: folder.id, dialog)
            }
            return
        }

        val mainDialog = AlertDialog.Builder(this)
            .setTitle("Edit Folder")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim()
                if (newName.isNotEmpty()) {
                    WhitelistManager.renameFolder(this, folder.id, newName)
                }
                WhitelistManager.setFolderHidden(this, folder.id, hiddenSwitch.isChecked)
                refreshHomeList()
            }
            .setNegativeButton("Cancel", null)
            .show()
        layout.findViewWithTag<MaterialButton>("lockInTurnOff")?.setOnClickListener {
            showDisableLockInPasswordDialog(effectiveLockInId ?: folder.id, mainDialog)
        }
    }

    private fun showUnblockPasswordDialog(folderId: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val passwordInput = EditText(this).apply {
            hint = "Enter password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(10))
        }
        layout.addView(passwordInput)

        AlertDialog.Builder(this)
            .setTitle("Verify Password")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Unblock", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val password = passwordInput.text.toString()
                        if (PasswordManager.verifyPassword(this@MainActivity, password)) {
                            WhitelistManager.unblockFolder(this@MainActivity, folderId)
                            dismiss()
                            refreshHomeList()
                            android.widget.Toast.makeText(this@MainActivity, "Folder unblocked", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            passwordInput.error = "Incorrect password"
                        }
                    }
                }
                show()
            }
    }

    private fun showFolderBlockedDialog(url: String, folderName: String?, timeRemaining: Long) {
        val hours = timeRemaining / 3600000
        val minutes = (timeRemaining % 3600000) / 60000
        val timeStr = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"

        AlertDialog.Builder(this)
            .setTitle("Folder Temporarily Blocked")
            .setMessage("The folder \"${folderName ?: "Unknown"}\" is blocked.\n\nTime remaining: $timeStr\n\nURL: $url")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showLockInWarningDialog(entry: WhitelistEntry, url: String, folderId: String, durationMinutes: Int) {
        AlertDialog.Builder(this)
            .setTitle("\u26A0\uFE0F Start Lock-in Mode?")
            .setMessage("You will be locked to \"${entry.name}\" for $durationMinutes minutes.\n\nAll other sites in this folder will be blocked until the timer expires.\n\nAre you sure?")
            .setPositiveButton("Lock In") { _, _ ->
                WhitelistManager.startLockInSession(this, folderId, entry.url)
                refreshHomeList()
                showWebView()
                binding.webView.loadUrl(url)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLockInBlockedDialog(url: String, timeRemaining: Long) {
        val minutes = (timeRemaining / 60000).coerceAtLeast(1)
        AlertDialog.Builder(this)
            .setTitle("Lock-in Mode Active")
            .setMessage("You are currently locked to another site.\n\nTime remaining: $minutes minute(s)")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showDisableLockInPasswordDialog(folderId: String, dialog: AlertDialog?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val passwordInput = EditText(this).apply {
            hint = "Enter password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(10))
        }
        layout.addView(passwordInput)

        AlertDialog.Builder(this)
            .setTitle("Verify Password")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Disable Lock-in", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val password = passwordInput.text.toString()
                        if (PasswordManager.verifyPassword(this@MainActivity, password)) {
                            WhitelistManager.setLockInEnabled(this@MainActivity, folderId, false)
                            dismiss()
                            dialog?.dismiss()
                            refreshHomeList()
                            android.widget.Toast.makeText(this@MainActivity, "Lock-in mode disabled", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            passwordInput.error = "Incorrect password"
                        }
                    }
                }
                show()
            }
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

        val suggestLabel = TextView(this).apply {
            text = "Existing tags"
            textSize = 11f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 16, 0, 4)
            visibility = View.GONE
        }
        layout.addView(suggestLabel)

        val suggestScroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        val suggestContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        suggestScroll.addView(suggestContainer)
        layout.addView(suggestScroll)

        fun rebuildSuggestions(query: String) {
            suggestContainer.removeAllViews()
            if (query.isEmpty()) {
                suggestLabel.visibility = View.GONE
                suggestScroll.visibility = View.GONE
                return
            }
            val filtered = allTags.filter { it !in currentTags && it.contains(query, ignoreCase = true) }
            if (filtered.isEmpty()) {
                suggestLabel.visibility = View.GONE
                suggestScroll.visibility = View.GONE
                return
            }
            for (tag in filtered) {
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
                            tagInput.text.clear()
                        }
                    }
                }
                suggestContainer.addView(chip)
            }
            suggestLabel.visibility = View.VISIBLE
            suggestScroll.visibility = View.VISIBLE
        }

        tagInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                rebuildSuggestions(s?.toString()?.trim()?.lowercase() ?: "")
            }
        })

        val hiddenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 0)
        }
        val hiddenLabel = TextView(this).apply {
            text = "Hidden"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val hiddenSwitch = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            isChecked = entry.hidden
        }
        hiddenRow.addView(hiddenLabel)
        hiddenRow.addView(hiddenSwitch)
        layout.addView(hiddenRow)

        val entryVideoId = VideoProgressManager.extractVideoId(entry.url)
        if (entryVideoId != null) {
            val resetBtn = com.google.android.material.button.MaterialButton(this).apply {
                text = "Reset Video Progress"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 24
                }
                setOnClickListener {
                    VideoProgressManager.resetProgress(this@MainActivity, entryVideoId)
                    android.widget.Toast.makeText(this@MainActivity, "Video progress reset", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            layout.addView(resetBtn)
        }

        val curatedFolders = WhitelistManager.getCuratedFolders(this)
        if (curatedFolders.isNotEmpty()) {
            val addToCuratedBtn = com.google.android.material.button.MaterialButton(this).apply {
                text = "Add to Curated Folder"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 24
                }
                setOnClickListener {
                    if (curatedFolders.size == 1) {
                        attemptAddToCurated(entry, curatedFolders[0])
                    } else {
                        val names = curatedFolders.map { "${it.iconEmoji ?: ""} ${it.name}" }.toTypedArray()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Select Curated Folder")
                            .setItems(names) { _, which ->
                                attemptAddToCurated(entry, curatedFolders[which])
                            }
                            .show()
                    }
                }
            }
            layout.addView(addToCuratedBtn)
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
                WhitelistManager.setEntryHidden(this, entry.url, hiddenSwitch.isChecked)
                refreshHomeList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun attemptAddToCurated(entry: WhitelistEntry, curatedFolder: Folder) {
        if (curatedFolder.preventEditWithoutPassword) {
            showPasswordDialogThen {
                doAddToCurated(entry, curatedFolder)
            }
            return
        }
        doAddToCurated(entry, curatedFolder)
    }

    private fun doAddToCurated(entry: WhitelistEntry, curatedFolder: Folder) {
        val currentCount = WhitelistManager.getEntriesInFolder(this, curatedFolder.id).size
        if (curatedFolder.maxSites != null && currentCount >= curatedFolder.maxSites) {
            android.widget.Toast.makeText(this, "Curated folder is full (max ${curatedFolder.maxSites} sites). Remove a site first.", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        val success = WhitelistManager.copyEntryToCuratedFolder(this, entry.url, curatedFolder.id)
        if (success) {
            android.widget.Toast.makeText(this, "Added to ${curatedFolder.name}", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(this, "Already in ${curatedFolder.name}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPasswordDialogThen(onSuccess: () -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        val passwordInput = EditText(this).apply {
            hint = "Enter password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(10))
        }
        layout.addView(passwordInput)

        AlertDialog.Builder(this)
            .setTitle("Verify Password")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val password = passwordInput.text.toString()
                        if (PasswordManager.verifyPassword(this@MainActivity, password)) {
                            dismiss()
                            onSuccess()
                        } else {
                            passwordInput.error = "Incorrect password"
                        }
                    }
                }
                show()
            }
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

    private val invidiousHosts = listOf("yewtu.be", "invidious.nadeko.net")

    private fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/") || lower.contains("youtu.be/")
    }

    private fun isInvidiousUrl(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        return invidiousHosts.any { host == it || host.endsWith(".$it") }
    }

    private fun rewriteYouTubeToInvidious(url: String): String {
        val uri = Uri.parse(url)
        if (uri.host == "youtu.be") {
            val videoId = uri.pathSegments.firstOrNull() ?: return url
            return "https://$invidiousInstance/watch?v=$videoId"
        }
        val builder = uri.buildUpon()
            .scheme("https")
            .authority(invidiousInstance)
        return builder.build().toString()
    }

    private fun invidiousToYouTubeUrl(url: String): String {
        val uri = Uri.parse(url)
        val builder = uri.buildUpon()
            .scheme("https")
            .authority("www.youtube.com")
        return builder.build().toString()
    }

    private fun isYouTubeWatchPage(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("youtube.com/watch") || lower.contains("youtu.be/")) return true
        val host = Uri.parse(url).host?.lowercase() ?: return false
        if (invidiousHosts.any { host == it || host.endsWith(".$it") }) {
            return lower.contains("/watch")
        }
        return false
    }

    private fun isYouTubeShorts(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com/shorts")
    }

    private fun showShortsBlockedDialog() {
        AlertDialog.Builder(this)
            .setTitle("YouTube Shorts Blocked")
            .setMessage("YouTube Shorts are blocked.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun extractYouTubeVideoId(url: String): String? {
        return VideoProgressManager.extractVideoId(url)
    }

    private fun buildEmbedHtml(videoId: String, title: String, tags: List<String>): String {
        val tagsHtml = if (tags.isNotEmpty()) {
            tags.joinToString(" ") { "<span class=\"tag\">${it.replace("<", "&lt;")}</span>" }
        } else ""

        val existingProgress = VideoProgressManager.getProgress(this, videoId)
        val initialPct = existingProgress?.percentage?.times(100)?.toInt() ?: 0
        val initialColor = if (existingProgress != null && existingProgress.isFinished) "#4CAF50" else "#1976D2"

        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          * { box-sizing: border-box; margin: 0; padding: 0; }
          body { background: #0f0f0f; color: #fff; font-family: -apple-system, sans-serif; }
          .header { padding: 16px; }
          #title { font-size: 18px; font-weight: 600; line-height: 1.3; }
          .tags { padding: 4px 16px 12px; display: flex; flex-wrap: wrap; gap: 8px; }
          .tag { background: #272727; color: #aaa; padding: 4px 12px; border-radius: 16px; font-size: 13px; }
          .video-container { width: 100%; aspect-ratio: 16/9; background: #000; }
          #progress-track { width: 100%; height: 4px; background: #272727; }
          #progress-fill { height: 100%; width: ${initialPct}%; background: ${initialColor}; transition: width 0.3s ease; }
        </style>
        </head>
        <body>
        <div class="header"><div id="title">Loading...</div></div>
        ${if (tags.isNotEmpty()) "<div class=\"tags\">${tagsHtml}</div>" else ""}
        <div class="video-container"><div id="player"></div></div>
        <div id="progress-track"><div id="progress-fill"></div></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
          var player;
          var progressInterval;
          function onYouTubeIframeAPIReady() {
            player = new YT.Player('player', {
              width: '100%',
              height: '100%',
              videoId: '${videoId}',
              playerVars: { autoplay: 1, modestbranding: 1, rel: 0 },
              events: {
                onReady: onPlayerReady,
                onStateChange: onPlayerStateChange
              }
            });
          }
          function onPlayerReady(event) {
            event.target.playVideo();
          }
          function updateProgressBar(ratio) {
            var pct = Math.min(Math.max(ratio * 100, 0), 100);
            var fill = document.getElementById('progress-fill');
            fill.style.width = pct + '%';
            fill.style.background = (pct >= 90) ? '#4CAF50' : '#1976D2';
          }
          function onPlayerStateChange(event) {
            if (event.data == YT.PlayerState.PLAYING) {
              progressInterval = setInterval(function() {
                var ct = player.getCurrentTime();
                var dur = player.getDuration();
                if (dur > 0) {
                  FocusBridge.updateProgress('${videoId}', ct, dur);
                  updateProgressBar(ct / dur);
                }
              }, 5000);
            } else {
              clearInterval(progressInterval);
              var ct = player.getCurrentTime();
              var dur = player.getDuration();
              if (dur > 0) {
                FocusBridge.updateProgress('${videoId}', ct, dur);
                updateProgressBar(ct / dur);
              }
            }
          }
          fetch('https://noembed.com/embed?url=https://www.youtube.com/watch?v=${videoId}')
            .then(function(r) { return r.json(); })
            .then(function(data) {
              if (data.title) document.getElementById('title').textContent = data.title;
              else document.getElementById('title').textContent = '';
            })
            .catch(function() { document.getElementById('title').textContent = ''; });
        </script>
        </body>
        </html>
        """.trimIndent()
    }

    private fun loadYouTubeEmbed(videoId: String, originalUrl: String) {
        currentEmbedVideoId = videoId
        showWebView()
        binding.urlBar.setText(originalUrl)

        val whitelist = WhitelistManager.getWhitelist(this)
        val matchingEntry = whitelist.find { entry ->
            val normalizedEntry = WhitelistManager.normalizeUrl(entry.url)
            val normalizedUrl = WhitelistManager.normalizeUrl(originalUrl)
            if (!normalizedEntry.contains("/")) {
                val urlDomain = normalizedUrl.substringBefore("/").substringBefore("?")
                urlDomain == normalizedEntry || urlDomain.endsWith(".$normalizedEntry")
            } else {
                normalizedUrl.startsWith(normalizedEntry)
            }
        }
        val tags = matchingEntry?.tags ?: emptyList()

        val html = buildEmbedHtml(videoId, "Loading...", tags)
        binding.webView.loadDataWithBaseURL("https://focus-embed.local/", html, "text/html", "UTF-8", null)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(android.view.Gravity.START)) {
            binding.drawerLayout.closeDrawers()
            return
        }
        if (fullscreenView != null) {
            exitFullscreen()
            return
        }
        if (binding.webView.visibility == View.VISIBLE) {
            if (currentEmbedVideoId != null) {
                currentEmbedVideoId = null
                showHome()
            } else if (binding.webView.canGoBack()) {
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
        data class EntryItem(val entry: WhitelistEntry, val isLockedOut: Boolean = false) : HomeItem()
    }

    private class HomeAdapter(
        items: List<HomeItem>,
        private val showTags: Boolean = false,
        private val showVideoProgress: Boolean = false,
        private val showConsumedToday: Boolean = false,
        private val wasVisitedToday: (String) -> Boolean = { false },
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
            val emojiView: TextView? = view.findViewWithTag("emojiIcon")
        }

        class EntryViewHolder(val wrapper: View) : RecyclerView.ViewHolder(wrapper) {
            val textView: TextView = wrapper.findViewById(android.R.id.text1)
            val deleteButton: ImageButton = wrapper.findViewById(android.R.id.button1)
            val progressTrack: View = wrapper.findViewWithTag("progressTrack")
            val progressFill: View = wrapper.findViewWithTag("progressFill")
            val tagsRow: LinearLayout = wrapper.findViewWithTag("tagsRow")
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
                    val emojiText = TextView(parent.context).apply {
                        tag = "emojiIcon"
                        textSize = 24f
                        val size = (32 * parent.context.resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, size).apply {
                            marginEnd = 16
                        }
                        gravity = android.view.Gravity.CENTER
                        visibility = View.GONE
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
                    layout.addView(emojiText)
                    layout.addView(text)
                    layout.addView(deleteBtn)
                    layout.addView(arrow)
                    FolderViewHolder(layout)
                }
                else -> {
                    val dp = parent.context.resources.displayMetrics.density
                    val wrapper = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    val row = LinearLayout(parent.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
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
                    row.addView(text)
                    row.addView(deleteBtn)
                    wrapper.addView(row)

                    val tagsRow = LinearLayout(parent.context).apply {
                        tag = "tagsRow"
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            marginStart = 48
                            marginEnd = 48
                            bottomMargin = (4 * dp).toInt()
                        }
                        visibility = View.GONE
                    }
                    wrapper.addView(tagsRow)

                    val progressTrack = android.widget.FrameLayout(parent.context).apply {
                        tag = "progressTrack"
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (4 * dp).toInt()
                        ).apply {
                            marginStart = 48
                            marginEnd = 48
                        }
                        setBackgroundColor(0xFF272727.toInt())
                        visibility = View.GONE
                    }
                    val progressFill = View(parent.context).apply {
                        tag = "progressFill"
                        layoutParams = android.widget.FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                    progressTrack.addView(progressFill)
                    wrapper.addView(progressTrack)

                    EntryViewHolder(wrapper)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = currentItems[position]) {
                is HomeItem.FolderItem -> {
                    val vh = holder as FolderViewHolder
                    val ctx = holder.itemView.context
                    val isBlocked = WhitelistManager.isFolderBlocked(ctx, item.folder.id)
                    val effectiveLockInId = WhitelistManager.getEffectiveLockInFolderId(ctx, item.folder.id)
                    val lockInActive = effectiveLockInId != null && WhitelistManager.getLockInSession(ctx, effectiveLockInId) != null
                    val lockInArmed = effectiveLockInId != null && !lockInActive
                    if (isBlocked) {
                        val remaining = WhitelistManager.getFolderBlockTimeRemaining(ctx, item.folder.id)
                        val hrs = remaining / 3600000
                        val mins = (remaining % 3600000) / 60000
                        val timeStr = if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
                        vh.textView.text = "\uD83D\uDD12 ${item.folder.name} ($timeStr)"
                    } else if (lockInActive) {
                        val session = WhitelistManager.getLockInSession(ctx, effectiveLockInId!!)
                        if (session != null) {
                            val remaining = session.second - System.currentTimeMillis()
                            val mins = (remaining / 60000).coerceAtLeast(1)
                            vh.textView.text = "\uD83D\uDD12 ${item.folder.name} (Locked ${mins}m)"
                        } else {
                            vh.textView.text = item.folder.name
                        }
                    } else if (lockInArmed) {
                        vh.textView.text = "\uD83D\uDD13 ${item.folder.name}"
                    } else {
                        vh.textView.text = item.folder.name
                    }
                    val folderAlpha = if (item.folder.hidden) 0.5f else if (isBlocked) 0.6f else 1.0f
                    vh.itemView.alpha = folderAlpha
                    if (item.folder.isCurated && item.folder.iconEmoji != null) {
                        vh.iconView.visibility = View.GONE
                        vh.emojiView?.visibility = View.VISIBLE
                        vh.emojiView?.text = item.folder.iconEmoji
                    } else {
                        vh.iconView.visibility = View.VISIBLE
                        vh.emojiView?.visibility = View.GONE
                        if (isBlocked) {
                            vh.iconView.setColorFilter(0xFFFF5722.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                        } else if (lockInActive) {
                            vh.iconView.setColorFilter(0xFFFF9800.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                        } else if (lockInArmed) {
                            vh.iconView.setColorFilter(0xFF2196F3.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                        } else {
                            vh.iconView.clearColorFilter()
                        }
                    }
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
                    val entryAlpha = if (item.isLockedOut) 0.5f else if (item.entry.hidden) 0.5f else 1.0f
                    vh.itemView.alpha = entryAlpha
                    vh.itemView.setOnClickListener { onEntryClick(item.entry) }
                    vh.itemView.setOnLongClickListener {
                        onEntryLongPress(item.entry)
                        true
                    }
                    vh.deleteButton.setOnClickListener { onEntryDelete(item.entry) }

                    if (showConsumedToday && wasVisitedToday(item.entry.url)) {
                        vh.textView.setTextColor(0xFF4CAF50.toInt())
                    } else {
                        val typedValue = android.util.TypedValue()
                        vh.itemView.context.theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
                        vh.textView.setTextColor(vh.itemView.context.getColor(typedValue.resourceId))
                    }

                    val dp = vh.itemView.context.resources.displayMetrics.density
                    if (showTags && item.entry.tags.isNotEmpty()) {
                        vh.tagsRow.removeAllViews()
                        for (tag in item.entry.tags) {
                            val chip = TextView(vh.itemView.context).apply {
                                text = tag
                                textSize = 11f
                                setPadding((8 * dp).toInt(), (2 * dp).toInt(), (8 * dp).toInt(), (2 * dp).toInt())
                                background = GradientDrawable().apply {
                                    cornerRadius = 12 * dp
                                    setColor(0x1F000000)
                                }
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    marginEnd = (6 * dp).toInt()
                                }
                            }
                            vh.tagsRow.addView(chip)
                        }
                        vh.tagsRow.visibility = View.VISIBLE
                    } else {
                        vh.tagsRow.visibility = View.GONE
                    }

                    if (showVideoProgress) {
                        val videoId = VideoProgressManager.extractVideoId(item.entry.url)
                        if (videoId != null) {
                            val progress = VideoProgressManager.getProgress(vh.itemView.context, videoId)
                            if (progress != null && progress.isStarted && progress.duration > 0) {
                                vh.progressTrack.visibility = View.VISIBLE
                                val color = if (progress.isFinished) 0xFF4CAF50.toInt() else 0xFF1976D2.toInt()
                                vh.progressFill.setBackgroundColor(color)
                                vh.progressTrack.post {
                                    val trackWidth = vh.progressTrack.width
                                    val fillWidth = (trackWidth * progress.percentage).toInt()
                                    vh.progressFill.layoutParams = vh.progressFill.layoutParams.apply {
                                        width = fillWidth
                                    }
                                }
                            } else {
                                vh.progressTrack.visibility = View.GONE
                            }
                        } else {
                            vh.progressTrack.visibility = View.GONE
                        }
                    } else {
                        vh.progressTrack.visibility = View.GONE
                    }
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
