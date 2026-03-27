package com.example.focuslock

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.focuslock.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var adapter: WhitelistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (PasswordManager.isPasswordSet(this)) {
            showVerifyPasswordDialog()
        } else {
            showCreatePasswordDialog()
        }
    }

    private fun initSettings() {
        adapter = WhitelistAdapter(
            WhitelistManager.getWhitelist(this).toMutableList(),
            onEdit = { entry -> showEditEntryDialog(entry) },
            onDelete = { entry ->
                WhitelistManager.removeEntry(this, entry.url)
                refreshList()
            }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.btnAdd.setOnClickListener { showAddEntryDialog() }
        binding.btnChangePassword.setOnClickListener { showChangePasswordFlow() }
        binding.btnClearData.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Browsing Data")
                .setMessage("Clear all browsing data? This will remove cookies, cache, and form data.")
                .setPositiveButton("Clear") { _, _ ->
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun refreshList() {
        adapter.updateList(WhitelistManager.getWhitelist(this).toMutableList())
    }

    private fun showCreatePasswordDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val passwordInput = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(10))
        }

        val confirmInput = EditText(this).apply {
            hint = "Confirm password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(10))
        }

        layout.addView(passwordInput)
        layout.addView(confirmInput)

        AlertDialog.Builder(this)
            .setTitle("Create Password")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val password = passwordInput.text.toString()
                        val confirm = confirmInput.text.toString()

                        if (password.isEmpty()) {
                            passwordInput.error = "Password cannot be empty"
                            return@setOnClickListener
                        }
                        if (password != confirm) {
                            confirmInput.error = "Passwords do not match"
                            return@setOnClickListener
                        }

                        PasswordManager.setPassword(this@SettingsActivity, password)
                        dismiss()
                        initSettings()
                    }
                }
                show()
            }
    }

    private fun showVerifyPasswordDialog() {
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
            .setTitle("Enter Password")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val password = passwordInput.text.toString()

                        if (PasswordManager.verifyPassword(this@SettingsActivity, password)) {
                            dismiss()
                            initSettings()
                        } else {
                            passwordInput.error = "Incorrect password"
                        }
                    }
                }
                show()
            }
    }

    private fun showAddEntryDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        val urlInput = EditText(this).apply {
            hint = "URL (e.g. instagram.com or instagram.com/direct/)"
        }
        val nameInput = EditText(this).apply {
            hint = "Display name (optional)"
        }
        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && nameInput.text.isNullOrBlank()) {
                val urlText = urlInput.text.toString().trim()
                if (urlText.isNotEmpty()) {
                    nameInput.hint = "Fetching title..."
                    TitleFetcher.fetch(urlText) { title ->
                        runOnUiThread {
                            nameInput.hint = "Display name (optional)"
                            if (title != null && nameInput.text.isNullOrBlank()) {
                                nameInput.setText(title)
                            }
                        }
                    }
                }
            }
        }
        layout.addView(urlInput)
        layout.addView(nameInput)

        AlertDialog.Builder(this)
            .setTitle("Add Whitelist Entry")
            .setView(layout)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val url = urlInput.text.toString().trim()
                        val name = nameInput.text.toString().trim()

                        if (url.isEmpty()) {
                            urlInput.error = "URL cannot be empty"
                            return@setOnClickListener
                        }

                        val normalizedUrl = WhitelistManager.normalizeUrl(url)
                        val existing = WhitelistManager.getWhitelist(this@SettingsActivity)
                        if (existing.any { it.url == normalizedUrl }) {
                            urlInput.error = "URL already exists"
                            return@setOnClickListener
                        }

                        WhitelistManager.addEntry(this@SettingsActivity, url, name)
                        refreshList()
                        dismiss()
                    }
                }
                show()
            }
    }

    private fun showEditEntryDialog(entry: WhitelistEntry) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Display name"
            setText(entry.name)
        }
        val urlInput = EditText(this).apply {
            hint = "URL"
            setText(entry.url)
        }
        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val urlText = urlInput.text.toString().trim()
                val currentName = nameInput.text.toString().trim()
                // Only auto-fetch if name is blank or still matches the URL (never been custom-named)
                if (urlText.isNotEmpty() && (currentName.isBlank() || currentName == entry.url || currentName == urlText)) {
                    nameInput.hint = "Fetching title..."
                    TitleFetcher.fetch(urlText) { title ->
                        runOnUiThread {
                            nameInput.hint = "Display name"
                            if (title != null) {
                                val stillAutoName = nameInput.text.toString().trim().let {
                                    it.isBlank() || it == entry.url || it == urlText
                                }
                                if (stillAutoName) {
                                    nameInput.setText(title)
                                }
                            }
                        }
                    }
                }
            }
        }
        layout.addView(nameInput)
        layout.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("Edit Whitelist Entry")
            .setView(layout)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val newUrl = urlInput.text.toString().trim()
                        val newName = nameInput.text.toString().trim()

                        if (newUrl.isEmpty()) {
                            urlInput.error = "URL cannot be empty"
                            return@setOnClickListener
                        }

                        val normalizedNew = WhitelistManager.normalizeUrl(newUrl)
                        if (normalizedNew != entry.url) {
                            val existing = WhitelistManager.getWhitelist(this@SettingsActivity)
                            if (existing.any { it.url == normalizedNew }) {
                                urlInput.error = "URL already exists"
                                return@setOnClickListener
                            }
                        }

                        WhitelistManager.updateEntry(this@SettingsActivity, entry.url, newUrl, newName)
                        refreshList()
                        dismiss()
                    }
                }
                show()
            }
    }

    private fun showChangePasswordFlow() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val oldPasswordInput = EditText(this).apply {
            hint = "Current password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(10))
        }

        layout.addView(oldPasswordInput)

        AlertDialog.Builder(this)
            .setTitle("Verify Current Password")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val oldPassword = oldPasswordInput.text.toString()

                        if (PasswordManager.verifyPassword(this@SettingsActivity, oldPassword)) {
                            dismiss()
                            showNewPasswordDialog()
                        } else {
                            oldPasswordInput.error = "Incorrect password"
                        }
                    }
                }
                show()
            }
    }

    private fun showNewPasswordDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
        }

        val passwordInput = EditText(this).apply {
            hint = "New password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(10))
        }

        val confirmInput = EditText(this).apply {
            hint = "Confirm password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(10))
        }

        layout.addView(passwordInput)
        layout.addView(confirmInput)

        AlertDialog.Builder(this)
            .setTitle("Create New Password")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val password = passwordInput.text.toString()
                        val confirm = confirmInput.text.toString()

                        if (password.isEmpty()) {
                            passwordInput.error = "Password cannot be empty"
                            return@setOnClickListener
                        }
                        if (password != confirm) {
                            confirmInput.error = "Passwords do not match"
                            return@setOnClickListener
                        }

                        PasswordManager.setPassword(this@SettingsActivity, password)
                        dismiss()
                        Toast.makeText(this@SettingsActivity, "Password changed", Toast.LENGTH_SHORT).show()
                    }
                }
                show()
            }
    }

    private class WhitelistAdapter(
        private var items: MutableList<WhitelistEntry>,
        private val onEdit: (WhitelistEntry) -> Unit,
        private val onDelete: (WhitelistEntry) -> Unit
    ) : RecyclerView.Adapter<WhitelistAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameView: TextView = view.findViewById(android.R.id.text1)
            val urlView: TextView = view.findViewById(android.R.id.text2)
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
            val textContainer = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameText = TextView(parent.context).apply {
                id = android.R.id.text1
                textSize = 16f
            }
            val urlText = TextView(parent.context).apply {
                id = android.R.id.text2
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
            }
            textContainer.addView(nameText)
            textContainer.addView(urlText)
            val button = ImageButton(parent.context).apply {
                id = android.R.id.button1
                setImageResource(android.R.drawable.ic_delete)
                setBackgroundResource(android.R.color.transparent)
                contentDescription = "Delete"
            }
            layout.addView(textContainer)
            layout.addView(button)
            return ViewHolder(layout)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            holder.nameView.text = entry.name
            holder.urlView.text = entry.url
            holder.itemView.setOnClickListener { onEdit(entry) }
            holder.deleteButton.setOnClickListener { onDelete(entry) }
        }

        override fun getItemCount(): Int = items.size

        fun updateList(newList: MutableList<WhitelistEntry>) {
            items = newList
            notifyDataSetChanged()
        }
    }
}
