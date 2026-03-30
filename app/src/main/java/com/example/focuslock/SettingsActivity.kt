package com.example.focuslock

import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
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
    private val folderStack = mutableListOf<String?>(null)
    private val currentFolderId: String? get() = folderStack.last()

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
        refreshList()
        binding.btnAdd.setOnClickListener { showAddEntryDialog() }
        binding.btnChangePassword.setOnClickListener { showChangePasswordFlow() }

        val prefs = getSharedPreferences("focus_lock_prefs", Context.MODE_PRIVATE)
        binding.switchYoutubeEmbed.isChecked = prefs.getBoolean("youtube_embed_mode", false)
        binding.switchYoutubeEmbed.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("youtube_embed_mode", isChecked).apply()
        }

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

    private fun buildSettingsList(): List<SettingsItem> {
        val items = mutableListOf<SettingsItem>()
        val subfolders = WhitelistManager.getSubfolders(this, currentFolderId)
        for (folder in subfolders) {
            items.add(SettingsItem.FolderItem(folder))
        }
        val entries = WhitelistManager.getEntriesInFolder(this, currentFolderId)
        for (entry in entries) {
            items.add(SettingsItem.EntryItem(entry))
        }
        return items
    }

    private fun refreshList() {
        val items = buildSettingsList()
        adapter = WhitelistAdapter(
            items,
            onFolderClick = { folder ->
                folderStack.add(folder.id)
                refreshList()
            },
            onFolderRename = { folder -> showRenameFolderDialog(folder) },
            onFolderDelete = { folder -> showDeleteFolderDialog(folder) },
            onEntryEdit = { entry -> showEditEntryDialog(entry) },
            onEntryDelete = { entry ->
                WhitelistManager.removeEntry(this, entry.url)
                refreshList()
            }
        )
        if (binding.recyclerView.layoutManager == null) {
            binding.recyclerView.layoutManager = LinearLayoutManager(this)
        }
        binding.recyclerView.adapter = adapter
        updateBreadcrumb()
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
                refreshList()
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
                        refreshList()
                    }
                }
            }
            container.addView(label)
        }

        binding.breadcrumbScroll.post {
            binding.breadcrumbScroll.fullScroll(android.widget.HorizontalScrollView.FOCUS_RIGHT)
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (folderStack.size > 1) {
            folderStack.removeAt(folderStack.size - 1)
            refreshList()
        } else {
            super.onBackPressed()
        }
    }

    private fun buildFolderChoices(): List<Pair<String?, String>> {
        val folders = WhitelistManager.getFolders(this)
        val result = mutableListOf<Pair<String?, String>>(null to "None (root)")
        fun addLevel(parentId: String?, indent: Int) {
            folders.filter { it.parentId == parentId }.forEach { folder ->
                val prefix = "\u00A0\u00A0".repeat(indent)
                result.add(folder.id to "$prefix${folder.name}")
                addLevel(folder.id, indent + 1)
            }
        }
        addLevel(null, 0)
        return result
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
        val fetchBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Fetch Title"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
        }
        fetchBtn.setOnClickListener {
            val urlText = urlInput.text.toString().trim()
            if (urlText.isEmpty()) {
                urlInput.error = "Enter a URL first"
                return@setOnClickListener
            }
            fetchBtn.isEnabled = false
            fetchBtn.text = "Fetching..."
            TitleFetcher.fetch(urlText) { title ->
                runOnUiThread {
                    fetchBtn.isEnabled = true
                    fetchBtn.text = "Fetch Title"
                    if (title != null) {
                        nameInput.setText(title)
                    } else {
                        Toast.makeText(this@SettingsActivity, "Could not fetch title", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        var selectedFolderId: String? = currentFolderId
        val folderChoices = buildFolderChoices()
        val currentIndex = folderChoices.indexOfFirst { it.first == currentFolderId }.coerceAtLeast(0)
        val folderSelector = TextView(this).apply {
            hint = "Folder: ${folderChoices[currentIndex].second}"
            setPadding(0, 24, 0, 24)
            textSize = 16f
            setOnClickListener {
                val names = folderChoices.map { it.second }.toTypedArray()
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Select Folder")
                    .setItems(names) { _, which ->
                        selectedFolderId = folderChoices[which].first
                        text = "Folder: ${names[which]}"
                    }
                    .show()
            }
        }

        layout.addView(urlInput)
        layout.addView(fetchBtn)
        layout.addView(nameInput)
        layout.addView(folderSelector)

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

                        WhitelistManager.addEntry(this@SettingsActivity, url, name, selectedFolderId)
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
        val urlInput = EditText(this).apply {
            hint = "URL"
            setText(entry.url)
        }
        val nameInput = EditText(this).apply {
            hint = "Display name"
            setText(entry.name)
        }
        val fetchBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Fetch Title"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
                bottomMargin = 8
            }
        }
        fetchBtn.setOnClickListener {
            val urlText = urlInput.text.toString().trim()
            if (urlText.isEmpty()) {
                urlInput.error = "Enter a URL first"
                return@setOnClickListener
            }
            fetchBtn.isEnabled = false
            fetchBtn.text = "Fetching..."
            TitleFetcher.fetch(urlText) { title ->
                runOnUiThread {
                    fetchBtn.isEnabled = true
                    fetchBtn.text = "Fetch Title"
                    if (title != null) {
                        nameInput.setText(title)
                    } else {
                        Toast.makeText(this@SettingsActivity, "Could not fetch title", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        var selectedFolderId: String? = entry.folderId
        val folderChoices = buildFolderChoices()
        val currentIndex = folderChoices.indexOfFirst { it.first == entry.folderId }.coerceAtLeast(0)
        val folderSelector = TextView(this).apply {
            text = "Folder: ${folderChoices[currentIndex].second}"
            setPadding(0, 24, 0, 24)
            textSize = 16f
            setOnClickListener {
                val names = folderChoices.map { it.second }.toTypedArray()
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Select Folder")
                    .setItems(names) { _, which ->
                        selectedFolderId = folderChoices[which].first
                        text = "Folder: ${names[which]}"
                    }
                    .show()
            }
        }

        layout.addView(urlInput)
        layout.addView(fetchBtn)
        layout.addView(nameInput)
        layout.addView(folderSelector)

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
                        if (selectedFolderId != entry.folderId) {
                            val urlToMove = WhitelistManager.normalizeUrl(newUrl)
                            WhitelistManager.moveEntryToFolder(this@SettingsActivity, urlToMove, selectedFolderId)
                        }
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
                    refreshList()
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
                WhitelistManager.deleteFolder(this, folder.id)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private sealed class SettingsItem {
        data class FolderItem(val folder: Folder) : SettingsItem()
        data class EntryItem(val entry: WhitelistEntry) : SettingsItem()
    }

    private class WhitelistAdapter(
        items: List<SettingsItem>,
        private val onFolderClick: (Folder) -> Unit,
        private val onFolderRename: (Folder) -> Unit,
        private val onFolderDelete: (Folder) -> Unit,
        private val onEntryEdit: (WhitelistEntry) -> Unit,
        private val onEntryDelete: (WhitelistEntry) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var currentItems: MutableList<SettingsItem> = items.toMutableList()

        companion object {
            private const val VIEW_TYPE_FOLDER = 0
            private const val VIEW_TYPE_ENTRY = 1
        }

        class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconView: ImageView = view.findViewById(android.R.id.icon)
            val nameView: TextView = view.findViewById(android.R.id.text1)
            val editButton: ImageButton = view.findViewById(android.R.id.edit)
            val deleteButton: ImageButton = view.findViewById(android.R.id.button1)
            val arrowView: TextView = view.findViewById(android.R.id.summary)
        }

        class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameView: TextView = view.findViewById(android.R.id.text1)
            val urlView: TextView = view.findViewById(android.R.id.text2)
            val deleteButton: ImageButton = view.findViewById(android.R.id.button1)
        }

        override fun getItemViewType(position: Int): Int {
            return when (currentItems[position]) {
                is SettingsItem.FolderItem -> VIEW_TYPE_FOLDER
                is SettingsItem.EntryItem -> VIEW_TYPE_ENTRY
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
                    val arrow = TextView(parent.context).apply {
                        id = android.R.id.summary
                        setText("\u203A")
                        textSize = 18f
                        val size = (24 * parent.context.resources.displayMetrics.density).toInt()
                        layoutParams = LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                    layout.addView(icon)
                    layout.addView(text)
                    layout.addView(editBtn)
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
                    EntryViewHolder(layout)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = currentItems[position]) {
                is SettingsItem.FolderItem -> {
                    val vh = holder as FolderViewHolder
                    vh.nameView.text = item.folder.name
                    vh.itemView.setOnClickListener { onFolderClick(item.folder) }
                    vh.editButton.setOnClickListener { onFolderRename(item.folder) }
                    vh.deleteButton.setOnClickListener { onFolderDelete(item.folder) }
                }
                is SettingsItem.EntryItem -> {
                    val vh = holder as EntryViewHolder
                    vh.nameView.text = item.entry.name
                    vh.urlView.text = item.entry.url
                    vh.itemView.setOnClickListener { onEntryEdit(item.entry) }
                    vh.deleteButton.setOnClickListener { onEntryDelete(item.entry) }
                }
            }
        }

        fun updateList(newItems: List<SettingsItem>) {
            currentItems = newItems.toMutableList()
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = currentItems.size
    }
}
