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
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.focuslock.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var desktopMode = false

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
            val color = com.google.android.material.R.attr.colorPrimary
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
        binding.urlBar.setText("")
        refreshHomeList()
    }

    private fun showWebView() {
        binding.homeScreen.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.fab.visibility = View.GONE
    }

    private fun refreshHomeList() {
        val whitelist = WhitelistManager.getWhitelist(this)
        if (whitelist.isEmpty()) {
            binding.homeList.visibility = View.GONE
            binding.emptyMessage.visibility = View.VISIBLE
        } else {
            binding.homeList.visibility = View.VISIBLE
            binding.emptyMessage.visibility = View.GONE
            binding.homeList.layoutManager = LinearLayoutManager(this)
            binding.homeList.adapter = HomeAdapter(
                whitelist.toMutableList(),
                onClick = { entry ->
                    val url = if (entry.url.startsWith("http://") || entry.url.startsWith("https://")) {
                        entry.url
                    } else {
                        "https://${entry.url}"
                    }
                    showWebView()
                    binding.webView.loadUrl(url)
                },
                onRename = { entry ->
                    showRenameDialog(entry)
                },
                onDelete = { entry ->
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
        }
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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
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

    private class HomeAdapter(
        private var items: MutableList<WhitelistEntry>,
        private val onClick: (WhitelistEntry) -> Unit,
        private val onRename: (WhitelistEntry) -> Unit,
        private val onDelete: (WhitelistEntry) -> Unit
    ) : RecyclerView.Adapter<HomeAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(android.R.id.text1)
            val editButton: ImageButton = view.findViewById(android.R.id.edit)
            val deleteButton: ImageButton = view.findViewById(android.R.id.button1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
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
            return ViewHolder(layout)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            holder.textView.text = entry.name
            holder.itemView.setOnClickListener { onClick(entry) }
            holder.editButton.setOnClickListener { onRename(entry) }
            holder.deleteButton.setOnClickListener { onDelete(entry) }
        }

        override fun getItemCount(): Int = items.size
    }
}
