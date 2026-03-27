package com.example.focuslock

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.webView.settings.javaScriptEnabled = true
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (request.isForMainFrame.not()) return false
                return if (WhitelistManager.isUrlAllowed(this@MainActivity, url)) {
                    false
                } else {
                    view?.post { showHome() }
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let { binding.urlBar.setText(it) }
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

        binding.fab.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (binding.homeScreen.visibility == View.VISIBLE) {
            refreshHomeList()
        }
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
        binding.homeScreen.visibility = View.VISIBLE
        binding.webView.visibility = View.GONE
        binding.urlBar.setText("")
        refreshHomeList()
    }

    private fun showWebView() {
        binding.homeScreen.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
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
                    val cleaned = entry.removeSuffix("*")
                    val url = if (cleaned.startsWith("http://") || cleaned.startsWith("https://")) {
                        cleaned
                    } else {
                        "https://$cleaned"
                    }
                    showWebView()
                    binding.webView.loadUrl(url)
                },
                onDelete = { entry ->
                    AlertDialog.Builder(this)
                        .setTitle("Remove Entry")
                        .setMessage("Are you sure you want to remove $entry?")
                        .setPositiveButton("Remove") { _, _ ->
                            WhitelistManager.removeEntry(this, entry)
                            refreshHomeList()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            )
        }
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
        private var items: MutableList<String>,
        private val onClick: (String) -> Unit,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<HomeAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(android.R.id.text1)
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

            val button = ImageButton(parent.context).apply {
                id = android.R.id.button1
                setImageResource(android.R.drawable.ic_delete)
                setBackgroundResource(android.R.color.transparent)
                contentDescription = "Delete"
            }

            layout.addView(text)
            layout.addView(button)

            return ViewHolder(layout)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            holder.textView.text = entry
            holder.itemView.setOnClickListener { onClick(entry) }
            holder.deleteButton.setOnClickListener { onDelete(entry) }
        }

        override fun getItemCount(): Int = items.size
    }
}
