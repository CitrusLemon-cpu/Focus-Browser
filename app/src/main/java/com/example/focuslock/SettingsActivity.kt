package com.example.focuslock

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.GestureDetectorCompat
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
    private var isSettingsArchiveOpen = false
    private var settingsArchiveDateViewMode = false
    private val settingsArchiveFolderStack = mutableListOf<String?>(null)
    private val currentSettingsArchiveFolderId: String? get() = settingsArchiveFolderStack.last()
    private var settingsArchiveSearchQuery = ""

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
        binding.btnAdd.setOnClickListener {
            val curatedFolder = if (currentFolderId != null) WhitelistManager.getFolders(this).find { it.id == currentFolderId } else null
            if (curatedFolder?.isCurated == true) {
                Toast.makeText(this, "Use long-press on a site to add it to this curated folder.", Toast.LENGTH_LONG).show()
            } else {
                showAddEntryDialog()
            }
        }
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

        binding.btnCreateCurated.setOnClickListener {
            showCreateCuratedFolderDialog()
        }

        binding.switchHideArchiveIfLockIn.isChecked = ArchiveManager.isHideIfLockInActive(this)
        binding.switchHideArchiveIfLockIn.setOnCheckedChangeListener { _, isChecked ->
            ArchiveManager.setHideIfLockInActive(this, isChecked)
        }

        val archiveLockInEnabled = ArchiveManager.isArchiveLockInEnabled(this)
        binding.switchArchiveLockIn.isChecked = archiveLockInEnabled
        binding.archiveLockInDurationContainer.visibility = if (archiveLockInEnabled) View.VISIBLE else View.GONE
        binding.archiveLockInDurationInput.setText(ArchiveManager.getArchiveLockInDurationMinutes(this).toString())
        binding.switchArchiveLockIn.setOnCheckedChangeListener { _, isChecked ->
            val duration = binding.archiveLockInDurationInput.text.toString().toIntOrNull()?.coerceIn(1, 1440) ?: 30
            ArchiveManager.setArchiveLockInEnabled(this, isChecked, duration)
            binding.archiveLockInDurationContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            refreshSettingsArchiveList()
        }
        binding.archiveLockInDurationInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && ArchiveManager.isArchiveLockInEnabled(this)) {
                val duration = binding.archiveLockInDurationInput.text.toString().toIntOrNull()?.coerceIn(1, 1440) ?: 30
                ArchiveManager.setArchiveLockInEnabled(this, true, duration)
                refreshSettingsArchiveList()
            }
        }

        settingsArchiveDateViewMode = ArchiveManager.isDateView(this)

        val archiveOpenGesture = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (velocityY < -900 && !binding.recyclerView.canScrollVertically(1)) {
                    if (!isSettingsArchiveOpen) {
                        showSettingsArchive()
                        return true
                    }
                }
                return false
            }
        })
        binding.recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                archiveOpenGesture.onTouchEvent(e)
                return false
            }
        })

        val archiveCloseGesture = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (velocityY > 900 && !binding.settingsArchiveList.canScrollVertically(-1)) {
                    if (settingsArchiveFolderStack.size <= 1) {
                        hideSettingsArchive()
                        return true
                    }
                }
                return false
            }
        })
        binding.settingsArchiveList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                archiveCloseGesture.onTouchEvent(e)
                return false
            }
        })

        binding.btnSettingsArchiveBack.setOnClickListener {
            if (settingsArchiveFolderStack.size > 1) {
                settingsArchiveFolderStack.removeAt(settingsArchiveFolderStack.size - 1)
                refreshSettingsArchiveList()
                updateSettingsArchiveBreadcrumb()
            } else {
                hideSettingsArchive()
            }
        }

        binding.btnSettingsArchiveDateView.setOnClickListener {
            settingsArchiveDateViewMode = !settingsArchiveDateViewMode
            ArchiveManager.setDateView(this, settingsArchiveDateViewMode)
            binding.btnSettingsArchiveDateView.text = if (settingsArchiveDateViewMode) "By Folder" else "By Date"
            refreshSettingsArchiveList()
            updateSettingsArchiveBreadcrumb()
        }

        binding.settingsArchiveSearchBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                settingsArchiveSearchQuery = s?.toString()?.trim() ?: ""
                refreshSettingsArchiveList()
                updateSettingsArchiveBreadcrumb()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun buildSettingsList(): List<SettingsItem> {
        val items = mutableListOf<SettingsItem>()
        val subfolders = WhitelistManager.getSubfolders(this, currentFolderId)
        if (currentFolderId == null) {
            val curated = subfolders.filter { it.isCurated }.sortedBy { it.sortOrder }
            val regular = subfolders.filter { !it.isCurated }.sortedBy { it.sortOrder }
            for (folder in curated) {
                items.add(SettingsItem.FolderItem(folder))
            }
            for (folder in regular) {
                items.add(SettingsItem.FolderItem(folder))
            }
        } else {
            for (folder in subfolders) {
                items.add(SettingsItem.FolderItem(folder))
            }
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
            onFolderLongPress = { folder -> showFolderMetadataDialog(folder) },
            onFolderDelete = { folder -> showDeleteFolderDialog(folder) },
            onEntryLongPress = { entry -> showEntryMetadataDialog(entry) },
            onEntryDelete = { entry ->
                val entryFolder = if (entry.folderId != null) WhitelistManager.getFolders(this).find { it.id == entry.folderId } else null
                if (entryFolder?.isCurated == true && entryFolder.preventEditWithoutPassword) {
                    showSettingsPasswordDialogThen {
                        WhitelistManager.removeEntryFromFolder(this, entry.url, entryFolder.id)
                        refreshList()
                    }
                } else if (entryFolder?.isCurated == true) {
                    WhitelistManager.removeEntryFromFolder(this, entry.url, entryFolder.id)
                    refreshList()
                } else {
                    WhitelistManager.removeEntry(this, entry.url)
                    refreshList()
                }
            }
        )
        if (binding.recyclerView.layoutManager == null) {
            binding.recyclerView.layoutManager = LinearLayoutManager(this)
        }
        binding.recyclerView.adapter = adapter
        updateBreadcrumb()
        binding.btnCreateCurated.visibility = if (currentFolderId == null) View.VISIBLE else View.GONE
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
        if (binding.settingsArchiveScreen.visibility == View.VISIBLE) {
            if (settingsArchiveFolderStack.size > 1) {
                settingsArchiveFolderStack.removeAt(settingsArchiveFolderStack.size - 1)
                refreshSettingsArchiveList()
                updateSettingsArchiveBreadcrumb()
            } else {
                hideSettingsArchive()
            }
            return
        }
        if (binding.settingsDrawerLayout.isDrawerOpen(android.view.Gravity.END)) {
            binding.settingsDrawerLayout.closeDrawer(android.view.Gravity.END)
        } else if (folderStack.size > 1) {
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
            folders.filter { it.parentId == parentId && !it.isCurated }.forEach { folder ->
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

    private fun showFolderMetadataDialog(folder: Folder) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val nameLabel = TextView(this).apply {
            text = "Folder Name"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
        }
        val nameInput = EditText(this).apply {
            setText(folder.name)
            hint = "Folder name"
        }
        layout.addView(nameLabel)
        layout.addView(nameInput)

        val parentLabel = TextView(this).apply {
            text = "Parent Folder"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 24, 0, 8)
        }
        layout.addView(parentLabel)

        var selectedParentId: String? = folder.parentId
        val allFolders = WhitelistManager.getFolders(this)
        val folderChoices = buildFolderChoices().filter { it.first != folder.id }

        fun isDescendant(parentId: String, targetId: String): Boolean {
            if (parentId == targetId) return true
            return allFolders.filter { it.parentId == parentId }.any { isDescendant(it.id, targetId) }
        }

        val validChoices = folderChoices.filter { choice ->
            choice.first == null || !isDescendant(folder.id, choice.first!!)
        }

        val currentIndex = validChoices.indexOfFirst { it.first == folder.parentId }.coerceAtLeast(0)
        val parentSelector = TextView(this).apply {
            text = validChoices[currentIndex].second
            textSize = 16f
            setPadding(0, 12, 0, 12)
            setOnClickListener {
                val names = validChoices.map { it.second }.toTypedArray()
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Select Parent Folder")
                    .setItems(names) { _, which ->
                        selectedParentId = validChoices[which].first
                        text = names[which]
                    }
                    .show()
            }
        }
        layout.addView(parentSelector)

        if (folder.isCurated) {
            parentLabel.visibility = View.GONE
            parentSelector.visibility = View.GONE
        }

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

        val lockInLabel = TextView(this).apply {
            text = "Lock-in Mode"
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 32, 0, 8)
        }
        layout.addView(lockInLabel)

        val lockInRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
        }
        val lockInRowLabel = TextView(this).apply {
            text = "Enable Lock-in Mode"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val lockInSwitch = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            isChecked = folder.lockInEnabled
        }
        lockInRow.addView(lockInRowLabel)
        lockInRow.addView(lockInSwitch)
        layout.addView(lockInRow)

        val durationLabel = TextView(this).apply {
            text = "Session Duration"
            textSize = 14f
            setPadding(0, 16, 0, 8)
            visibility = if (folder.lockInEnabled) View.VISIBLE else View.GONE
        }
        layout.addView(durationLabel)

        val durationOptions = listOf(15, 30, 45, 60, 90, 120)
        val durationNames = durationOptions.map { if (it >= 60) "${it / 60}h ${if (it % 60 > 0) "${it % 60}m" else ""}" else "${it}m" }
        var selectedDurationMinutes = folder.lockInDurationMinutes
        val durationSelector = TextView(this).apply {
            val currentIdx = durationOptions.indexOf(folder.lockInDurationMinutes).coerceAtLeast(0)
            text = durationNames[currentIdx]
            textSize = 16f
            setPadding(0, 8, 0, 8)
            visibility = if (folder.lockInEnabled) View.VISIBLE else View.GONE
            setOnClickListener {
                val names = durationNames.toTypedArray()
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Select Duration")
                    .setItems(names) { _, which ->
                        selectedDurationMinutes = durationOptions[which]
                        text = names[which]
                    }
                    .show()
            }
        }
        layout.addView(durationSelector)

        val warningRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 0)
            visibility = if (folder.lockInEnabled) View.VISIBLE else View.GONE
        }
        val warningLabel = TextView(this).apply {
            text = "Show warning before each session"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val warningSwitch = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            isChecked = folder.lockInWarningEnabled
        }
        warningRow.addView(warningLabel)
        warningRow.addView(warningSwitch)
        layout.addView(warningRow)

        lockInSwitch.setOnCheckedChangeListener { _, isChecked ->
            val vis = if (isChecked) View.VISIBLE else View.GONE
            durationLabel.visibility = vis
            durationSelector.visibility = vis
            warningRow.visibility = vis
        }

        var selectedEmoji = folder.iconEmoji ?: "⭐"
        var selectedMaxSites: Int? = folder.maxSites
        val preventEditSwitch: com.google.android.material.switchmaterial.SwitchMaterial?
        val ignoreLockInSwitch: com.google.android.material.switchmaterial.SwitchMaterial?

        if (folder.isCurated) {
            val curatedLabel = TextView(this).apply {
                text = "Curated Folder Settings"
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
                setPadding(0, 32, 0, 8)
            }
            layout.addView(curatedLabel)

            val iconLabel = TextView(this).apply {
                text = "Folder Icon"
                textSize = 14f
                setPadding(0, 8, 0, 8)
            }
            layout.addView(iconLabel)

            val emojis = listOf("⭐", "📌", "🎯", "🔥", "💎", "📺", "🎬", "📋", "🗂️", "✨", "🏆", "❤️", "📚", "🎵", "🌟", "👀")
            val emojiGrid = android.widget.GridLayout(this).apply {
                columnCount = 8
                setPadding(0, 4, 0, 16)
            }
            val dp = resources.displayMetrics.density
            val emojiViews = mutableListOf<TextView>()
            for (emoji in emojis) {
                val emojiBtn = TextView(this).apply {
                    text = emoji
                    textSize = 22f
                    gravity = android.view.Gravity.CENTER
                    val size = (40 * dp).toInt()
                    layoutParams = android.widget.GridLayout.LayoutParams().apply {
                        width = size
                        height = size
                        setMargins((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
                    }
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 8 * dp
                        setColor(if (emoji == selectedEmoji) 0x302196F3 else 0x00000000)
                    }
                    background = bg
                    setOnClickListener {
                        selectedEmoji = emoji
                        for (ev in emojiViews) {
                            val evBg = ev.background as? android.graphics.drawable.GradientDrawable
                            evBg?.setColor(if (ev.text == emoji) 0x302196F3 else 0x00000000)
                        }
                    }
                }
                emojiViews.add(emojiBtn)
                emojiGrid.addView(emojiBtn)
            }
            layout.addView(emojiGrid)

            val maxSitesLabel = TextView(this).apply {
                text = "Maximum Sites"
                textSize = 14f
                setPadding(0, 8, 0, 4)
            }
            layout.addView(maxSitesLabel)

            val maxSitesInput = EditText(this).apply {
                hint = "Unlimited"
                inputType = InputType.TYPE_CLASS_NUMBER
                if (folder.maxSites != null) setText(folder.maxSites.toString())
            }
            layout.addView(maxSitesInput)

            val preventEditRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 16, 0, 0)
            }
            val preventEditLabel = TextView(this).apply {
                text = "Require password to add/remove sites"
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val pSwitch = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
                isChecked = folder.preventEditWithoutPassword
            }
            preventEditRow.addView(preventEditLabel)
            preventEditRow.addView(pSwitch)
            layout.addView(preventEditRow)
            preventEditSwitch = pSwitch

            val ignoreLockInRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 16, 0, 0)
            }
            val ignoreLockInLabel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            ignoreLockInLabel.addView(TextView(this).apply {
                text = "Ignore lock-in mode"
                textSize = 14f
            })
            ignoreLockInLabel.addView(TextView(this).apply {
                text = "Sites bypass lock-in restrictions from other folders"
                textSize = 11f
                setTextColor(android.graphics.Color.GRAY)
            })
            val iSwitch = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
                isChecked = folder.ignoreLockInMode
            }
            ignoreLockInRow.addView(ignoreLockInLabel)
            ignoreLockInRow.addView(iSwitch)
            layout.addView(ignoreLockInRow)
            ignoreLockInSwitch = iSwitch

            val scrollView = android.widget.ScrollView(this).apply { addView(layout) }

            AlertDialog.Builder(this)
                .setTitle("Edit Curated Folder")
                .setView(scrollView)
                .setPositiveButton("Save") { _, _ ->
                    val newName = nameInput.text.toString().trim()
                    if (newName.isNotEmpty()) {
                        WhitelistManager.renameFolder(this, folder.id, newName)
                    }
                    WhitelistManager.setFolderHidden(this, folder.id, hiddenSwitch.isChecked)
                    WhitelistManager.setCuratedFolderIcon(this, folder.id, selectedEmoji)
                    val maxSitesVal = maxSitesInput.text.toString().trim()
                    val maxSitesInt = maxSitesVal.toIntOrNull()
                    WhitelistManager.setCuratedFolderMaxSites(this, folder.id, if (maxSitesInt != null && maxSitesInt > 0) maxSitesInt else null)
                    WhitelistManager.setCuratedPreventEdit(this, folder.id, preventEditSwitch!!.isChecked)
                    WhitelistManager.setCuratedIgnoreLockIn(this, folder.id, ignoreLockInSwitch!!.isChecked)
                    WhitelistManager.setLockInEnabled(this, folder.id, lockInSwitch.isChecked, selectedDurationMinutes)
                    WhitelistManager.setLockInWarningEnabled(this, folder.id, warningSwitch.isChecked)
                    refreshList()
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        } else {
            preventEditSwitch = null
            ignoreLockInSwitch = null
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Folder")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim()
                if (newName.isNotEmpty()) {
                    WhitelistManager.renameFolder(this, folder.id, newName)
                }
                if (selectedParentId != folder.parentId) {
                    WhitelistManager.moveFolderToParent(this, folder.id, selectedParentId)
                }
                WhitelistManager.setFolderHidden(this, folder.id, hiddenSwitch.isChecked)
                WhitelistManager.setLockInEnabled(this, folder.id, lockInSwitch.isChecked, selectedDurationMinutes)
                WhitelistManager.setLockInWarningEnabled(this, folder.id, warningSwitch.isChecked)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEntryMetadataDialog(entry: WhitelistEntry) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val urlInput = EditText(this).apply {
            hint = "URL"
            setText(entry.url)
        }
        layout.addView(urlInput)

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
        layout.addView(fetchBtn)

        val nameInput = EditText(this).apply {
            hint = "Display name"
            setText(entry.name)
        }
        layout.addView(nameInput)

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
        layout.addView(folderSelector)

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

        val clearDataBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Clear Site Data"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }
        layout.addView(clearDataBtn)

        clearDataBtn.setOnClickListener {
            val rawUrl = entry.url.substringBefore("/")
            val siteUrl = "https://$rawUrl"

            val cookieMgr = android.webkit.CookieManager.getInstance()
            val cookies = cookieMgr.getCookie(siteUrl)
            if (!cookies.isNullOrEmpty()) {
                cookies.split(";").forEach { cookie ->
                    val name = cookie.trim().substringBefore("=")
                    if (name.isNotEmpty()) {
                        cookieMgr.setCookie(siteUrl, "$name=; Max-Age=0; path=/")
                        cookieMgr.setCookie(".$rawUrl", "$name=; Max-Age=0; path=/")
                    }
                }
            }
            cookieMgr.flush()

            android.webkit.WebStorage.getInstance().deleteOrigin(siteUrl)
            android.webkit.WebStorage.getInstance().deleteOrigin("https://www.$rawUrl")

            android.widget.Toast.makeText(this, "Site data cleared", android.widget.Toast.LENGTH_SHORT).show()
        }

        var descInput: EditText? = null
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
                    VideoProgressManager.resetProgress(this@SettingsActivity, entryVideoId)
                    Toast.makeText(this@SettingsActivity, "Video progress reset", Toast.LENGTH_SHORT).show()
                }
            }
            layout.addView(resetBtn)

            val descLabel = TextView(this).apply {
                text = "Notes / Description"
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
                setPadding(0, 24, 0, 8)
            }
            layout.addView(descLabel)

            descInput = EditText(this).apply {
                hint = "Add a note about this video..."
                setText(entry.description ?: "")
                minLines = 2
                maxLines = 5
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            layout.addView(descInput)
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
                        settingsAttemptAddToCurated(entry, curatedFolders[0])
                    } else {
                        val names = curatedFolders.map { "${it.iconEmoji ?: ""} ${it.name}" }.toTypedArray()
                        AlertDialog.Builder(this@SettingsActivity)
                            .setTitle("Select Curated Folder")
                            .setItems(names) { _, which ->
                                settingsAttemptAddToCurated(entry, curatedFolders[which])
                            }
                            .show()
                    }
                }
            }
            layout.addView(addToCuratedBtn)
        }

        val scrollView = android.widget.ScrollView(this).apply { addView(layout) }

        AlertDialog.Builder(this)
            .setTitle("Edit Site")
            .setView(scrollView)
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
                        val urlToUse = WhitelistManager.normalizeUrl(newUrl)
                        WhitelistManager.setEntryTags(this@SettingsActivity, urlToUse, currentTags)
                        if (selectedFolderId != entry.folderId) {
                            WhitelistManager.moveEntryToFolder(this@SettingsActivity, urlToUse, selectedFolderId)
                        }
                        WhitelistManager.setEntryHidden(this@SettingsActivity, urlToUse, hiddenSwitch.isChecked)
                        descInput?.let { WhitelistManager.setEntryDescription(this@SettingsActivity, urlToUse, it.text.toString().trim()) }
                        refreshList()
                        dismiss()
                    }
                }
                show()
            }
    }

    private fun showSettingsArchive() {
        isSettingsArchiveOpen = true
        settingsArchiveFolderStack.clear()
        settingsArchiveFolderStack.add(null)
        settingsArchiveSearchQuery = ""
        binding.settingsArchiveSearchBar.setText("")
        binding.settingsArchiveScreen.visibility = View.VISIBLE
        binding.settingsArchiveScreen.post {
            binding.settingsArchiveScreen.translationY = binding.settingsArchiveScreen.height.toFloat()
            binding.settingsArchiveScreen.animate()
                .translationY(0f)
                .setDuration(300)
                .start()
        }
        binding.btnSettingsArchiveDateView.text = if (settingsArchiveDateViewMode) "By Folder" else "By Date"
        refreshSettingsArchiveList()
        updateSettingsArchiveBreadcrumb()
    }

    private fun hideSettingsArchive() {
        binding.settingsArchiveScreen.animate()
            .translationY(binding.settingsArchiveScreen.height.toFloat())
            .setDuration(250)
            .withEndAction {
                binding.settingsArchiveScreen.visibility = View.GONE
                binding.settingsArchiveScreen.translationY = 0f
                isSettingsArchiveOpen = false
            }
            .start()
    }

    private fun refreshSettingsArchiveList() {
        if (!::binding.isInitialized) return
        val items = mutableListOf<SettingsArchiveItem>()

        if (settingsArchiveSearchQuery.isNotEmpty()) {
            val query = settingsArchiveSearchQuery.lowercase()
            val matches = ArchiveManager.getArchivedEntries(this).filter {
                it.name.lowercase().contains(query) || it.url.lowercase().contains(query)
            }
            for (entry in matches) {
                items.add(SettingsArchiveItem.EntryItem(entry, false))
            }
        } else if (settingsArchiveDateViewMode) {
            val byMonth = ArchiveManager.getArchivedEntriesByMonth(this)
            for ((label, entries) in byMonth) {
                items.add(SettingsArchiveItem.DateHeader(label))
                for (entry in entries) {
                    items.add(SettingsArchiveItem.EntryItem(entry, false))
                }
            }
        } else {
            val folders = ArchiveManager.getArchiveSubfolders(this, currentSettingsArchiveFolderId)
            for (folder in folders.sortedBy { it.sortOrder }) {
                items.add(SettingsArchiveItem.FolderItem(folder))
            }
            val entries = ArchiveManager.getEntriesInArchiveFolder(this, currentSettingsArchiveFolderId)
            for (entry in entries.sortedBy { it.sortOrder }) {
                items.add(SettingsArchiveItem.EntryItem(entry, false))
            }
        }

        binding.settingsArchiveList.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        binding.settingsArchiveEmptyMessage.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        if (binding.settingsArchiveList.layoutManager == null) {
            binding.settingsArchiveList.layoutManager = LinearLayoutManager(this)
        }
        binding.settingsArchiveList.adapter = SettingsArchiveAdapter(
            items,
            onFolderClick = { folder ->
                settingsArchiveFolderStack.add(folder.id)
                refreshSettingsArchiveList()
                updateSettingsArchiveBreadcrumb()
            },
            onFolderLongPress = { folder -> showSettingsArchiveFolderOptionsDialog(folder) },
            onEntryClick = { entry -> handleSettingsArchiveEntryTap(entry) },
            onEntryLongPress = { entry -> showArchiveEntryEditDialog(entry) }
        )
    }

    private fun handleSettingsArchiveEntryTap(entry: ArchivedEntry) {
        showSettingsArchiveRestoreDialog(entry)
    }

    private fun showSettingsArchiveRestoreDialog(entry: ArchivedEntry) {
        val folderChoices = buildFolderChoices()
        var selectedFolderId: String? = null

        val folderSelector = TextView(this).apply {
            text = "Restore to: Root"
            setPadding(0, 16, 0, 16)
            textSize = 15f
            setOnClickListener {
                val names = folderChoices.map { it.second }.toTypedArray()
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Restore To Folder")
                    .setItems(names) { _, which ->
                        selectedFolderId = folderChoices[which].first
                        text = "Restore to: ${folderChoices[which].second}"
                    }
                    .show()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
            addView(folderSelector)
        }

        AlertDialog.Builder(this)
            .setTitle("Restore \"${entry.name}\"?")
            .setView(layout)
            .setPositiveButton("Restore") { _, _ ->
                ArchiveManager.restoreEntry(this, entry, selectedFolderId)
                refreshSettingsArchiveList()
                refreshList()
                Toast.makeText(this, "${entry.name} restored", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showArchiveEntryEditDialog(entry: ArchivedEntry) {
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
                    text = "$tag  ✕"
                    textSize = 13f
                    setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                    background = GradientDrawable().apply {
                        cornerRadius = 16 * dp
                        setColor(0x1F000000)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = (8 * dp).toInt()
                    }
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
        }

        val allTags = (WhitelistManager.getAllTags(this) + ArchiveManager.getArchivedEntries(this).flatMap { it.tags }).toSet()

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
            val filtered = allTags.filter { it !in currentTags && it.contains(query, ignoreCase = true) }.sorted()
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
                    background = GradientDrawable().apply {
                        cornerRadius = 16 * dp
                        setColor(0x0F000000)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = (8 * dp).toInt()
                    }
                    setOnClickListener {
                        if (tag !in currentTags) {
                            currentTags.add(tag)
                            rebuildChips()
                            tagInput.text.clear()
                            rebuildSuggestions("")
                        }
                    }
                }
                suggestContainer.addView(chip)
            }
            suggestLabel.visibility = View.VISIBLE
            suggestScroll.visibility = View.VISIBLE
        }

        fun addTag() {
            val tag = tagInput.text.toString().trim().lowercase()
            if (tag.isNotEmpty() && tag !in currentTags) {
                currentTags.add(tag)
                tagInput.text.clear()
                rebuildChips()
                rebuildSuggestions("")
            }
        }

        addBtn.setOnClickListener { addTag() }
        tagInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                addTag()
                true
            } else {
                false
            }
        }

        addTagRow.addView(tagInput)
        addTagRow.addView(addBtn)
        layout.addView(addTagRow)

        tagInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                rebuildSuggestions(s?.toString()?.trim()?.lowercase() ?: "")
            }
        })

        var descInput: EditText? = null
        if (VideoProgressManager.extractVideoId(entry.url) != null) {
            val descLabel = TextView(this).apply {
                text = "Notes / Description"
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
                setPadding(0, 24, 0, 8)
            }
            layout.addView(descLabel)

            descInput = EditText(this).apply {
                hint = "Add a note about this video..."
                setText(entry.description ?: "")
                minLines = 2
                maxLines = 5
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            layout.addView(descInput)
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(layout)
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Archived Site")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val newName = nameInput.text.toString().trim().ifEmpty { entry.url }
                ArchiveManager.updateArchivedEntry(
                    this,
                    entry.url,
                    newName,
                    currentTags,
                    descInput?.text?.toString()?.trim()
                )
                refreshSettingsArchiveList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsArchiveFolderOptionsDialog(folder: ArchiveFolder) {
        val options = arrayOf("Rename", "Delete")
        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val input = EditText(this).apply { setText(folder.name) }
                        AlertDialog.Builder(this)
                            .setTitle("Rename Folder")
                            .setView(LinearLayout(this).apply {
                                setPadding(48, 32, 48, 0)
                                addView(input)
                            })
                            .setPositiveButton("Save") { _, _ ->
                                val newName = input.text.toString().trim()
                                if (newName.isNotEmpty()) {
                                    ArchiveManager.renameArchiveFolder(this, folder.id, newName)
                                    refreshSettingsArchiveList()
                                    updateSettingsArchiveBreadcrumb()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    1 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Delete Folder")
                            .setMessage("Delete \"${folder.name}\"? Sites inside will move to the parent folder.")
                            .setPositiveButton("Delete") { _, _ ->
                                ArchiveManager.deleteArchiveFolder(this, folder.id)
                                val remaining = ArchiveManager.getArchiveFolders(this).map { it.id }.toSet()
                                settingsArchiveFolderStack.removeAll { it != null && it !in remaining }
                                if (settingsArchiveFolderStack.isEmpty()) settingsArchiveFolderStack.add(null)
                                refreshSettingsArchiveList()
                                updateSettingsArchiveBreadcrumb()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun updateSettingsArchiveBreadcrumb() {
        val container = binding.settingsArchiveBreadcrumbContainer
        container.removeAllViews()

        if (settingsArchiveDateViewMode || settingsArchiveSearchQuery.isNotEmpty() || settingsArchiveFolderStack.size <= 1) {
            binding.settingsArchiveBreadcrumbScroll.visibility = View.GONE
            return
        }

        binding.settingsArchiveBreadcrumbScroll.visibility = View.VISIBLE
        val allFolders = ArchiveManager.getArchiveFolders(this)

        val homeText = TextView(this).apply {
            text = "Home"
            textSize = 14f
            setTextColor(getColor(com.google.android.material.R.color.design_default_color_primary))
            setOnClickListener {
                settingsArchiveFolderStack.clear()
                settingsArchiveFolderStack.add(null)
                refreshSettingsArchiveList()
                updateSettingsArchiveBreadcrumb()
            }
        }
        container.addView(homeText)

        for (i in 1 until settingsArchiveFolderStack.size) {
            val sep = TextView(this).apply {
                text = " › "
                textSize = 14f
            }
            container.addView(sep)

            val folderId = settingsArchiveFolderStack[i]!!
            val folder = allFolders.find { it.id == folderId }
            val isLast = i == settingsArchiveFolderStack.size - 1

            val label = TextView(this).apply {
                text = folder?.name ?: "…"
                textSize = 14f
                if (isLast) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                } else {
                    setTextColor(getColor(com.google.android.material.R.color.design_default_color_primary))
                    setOnClickListener {
                        while (settingsArchiveFolderStack.size > i + 1) {
                            settingsArchiveFolderStack.removeAt(settingsArchiveFolderStack.size - 1)
                        }
                        refreshSettingsArchiveList()
                        updateSettingsArchiveBreadcrumb()
                    }
                }
            }
            container.addView(label)
        }

        binding.settingsArchiveBreadcrumbScroll.post {
            binding.settingsArchiveBreadcrumbScroll.fullScroll(android.widget.HorizontalScrollView.FOCUS_RIGHT)
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

    private fun showDeleteFolderDialog(folder: Folder) {
        val allFolders = WhitelistManager.getFolders(this)
        fun countEntries(folderId: String): Int {
            val direct = WhitelistManager.getEntriesInFolder(this, folderId).size
            val childFolders = allFolders.filter { it.parentId == folderId }
            return direct + childFolders.sumOf { countEntries(it.id) }
        }
        val entryCount = countEntries(folder.id)
        val message = if (folder.isCurated) {
            if (entryCount > 0) {
                "Delete curated folder \"${folder.name}\" and its $entryCount copied site(s)? Original entries will remain in their source folders."
            } else {
                "Delete empty curated folder \"${folder.name}\"?"
            }
        } else {
            if (entryCount > 0) {
                "Delete \"${folder.name}\" and its $entryCount site(s)? Those sites will be blocked again."
            } else {
                "Delete empty folder \"${folder.name}\"?"
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Delete Folder")
            .setMessage(message)
            .setPositiveButton("Delete") { _, _ ->
                WhitelistManager.deleteFolder(this, folder.id)
                val remainingFolders = WhitelistManager.getFolders(this@SettingsActivity)
                val remainingIds = remainingFolders.map { it.id }.toSet()
                folderStack.removeAll { it != null && it !in remainingIds }
                if (folderStack.isEmpty()) folderStack.add(null)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateCuratedFolderDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val nameInput = EditText(this).apply {
            hint = "Folder name"
            setText("Curated")
        }
        layout.addView(nameInput)

        val iconLabel = TextView(this).apply {
            text = "Select Icon"
            textSize = 14f
            setPadding(0, 24, 0, 8)
        }
        layout.addView(iconLabel)

        val emojis = listOf("⭐", "📌", "🎯", "🔥", "💎", "📺", "🎬", "📋", "🗂️", "✨", "🏆", "❤️", "📚", "🎵", "🌟", "👀")
        var selectedEmoji = "⭐"
        val dp = resources.displayMetrics.density
        val emojiGrid = android.widget.GridLayout(this).apply {
            columnCount = 8
            setPadding(0, 4, 0, 16)
        }
        val emojiViews = mutableListOf<TextView>()
        for (emoji in emojis) {
            val emojiBtn = TextView(this).apply {
                text = emoji
                textSize = 22f
                gravity = android.view.Gravity.CENTER
                val size = (40 * dp).toInt()
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
                }
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8 * dp
                    setColor(if (emoji == selectedEmoji) 0x302196F3 else 0x00000000)
                }
                background = bg
                setOnClickListener {
                    selectedEmoji = emoji
                    for (ev in emojiViews) {
                        val evBg = ev.background as? android.graphics.drawable.GradientDrawable
                        evBg?.setColor(if (ev.text == emoji) 0x302196F3 else 0x00000000)
                    }
                }
            }
            emojiViews.add(emojiBtn)
            emojiGrid.addView(emojiBtn)
        }
        layout.addView(emojiGrid)

        AlertDialog.Builder(this)
            .setTitle("New Curated Folder")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    WhitelistManager.createCuratedFolder(this, name, selectedEmoji)
                    refreshList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsPasswordDialogThen(onSuccess: () -> Unit) {
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
                        if (PasswordManager.verifyPassword(this@SettingsActivity, password)) {
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

    private fun settingsAttemptAddToCurated(entry: WhitelistEntry, curatedFolder: Folder) {
        if (curatedFolder.preventEditWithoutPassword) {
            showSettingsPasswordDialogThen {
                settingsDoAddToCurated(entry, curatedFolder)
            }
            return
        }
        settingsDoAddToCurated(entry, curatedFolder)
    }

    private fun settingsDoAddToCurated(entry: WhitelistEntry, curatedFolder: Folder) {
        val currentCount = WhitelistManager.getEntriesInFolder(this, curatedFolder.id).size
        if (curatedFolder.maxSites != null && currentCount >= curatedFolder.maxSites) {
            Toast.makeText(this, "Curated folder is full (max ${curatedFolder.maxSites} sites). Remove a site first.", Toast.LENGTH_LONG).show()
            return
        }

        val success = WhitelistManager.copyEntryToCuratedFolder(this, entry.url, curatedFolder.id)
        if (success) {
            Toast.makeText(this, "Added to ${curatedFolder.name}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Already in ${curatedFolder.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private sealed class SettingsArchiveItem {
        data class FolderItem(val folder: ArchiveFolder) : SettingsArchiveItem()
        data class EntryItem(val entry: ArchivedEntry, val isLocked: Boolean = false) : SettingsArchiveItem()
        data class DateHeader(val label: String) : SettingsArchiveItem()
    }

    private class SettingsArchiveAdapter(
        private val items: List<SettingsArchiveItem>,
        private val onFolderClick: (ArchiveFolder) -> Unit,
        private val onFolderLongPress: (ArchiveFolder) -> Unit,
        private val onEntryClick: (ArchivedEntry) -> Unit,
        private val onEntryLongPress: (ArchivedEntry) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            private const val TYPE_FOLDER = 0
            private const val TYPE_ENTRY = 1
            private const val TYPE_DATE_HEADER = 2
        }

        class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val iconView: ImageView = view.findViewById(android.R.id.icon)
            val textView: TextView = view.findViewById(android.R.id.text1)
            val chevronView: TextView = view.findViewById(android.R.id.text2)
        }

        class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameView: TextView = view.findViewById(android.R.id.text1)
            val urlView: TextView = view.findViewById(android.R.id.text2)
        }

        class DateHeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view as TextView
        }

        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is SettingsArchiveItem.FolderItem -> TYPE_FOLDER
            is SettingsArchiveItem.EntryItem -> TYPE_ENTRY
            is SettingsArchiveItem.DateHeader -> TYPE_DATE_HEADER
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            val dp = ctx.resources.displayMetrics.density
            return when (viewType) {
                TYPE_FOLDER -> {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                        val outValue = android.util.TypedValue()
                        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                        setBackgroundResource(outValue.resourceId)
                    }
                    val icon = ImageView(ctx).apply {
                        id = android.R.id.icon
                        setImageResource(android.R.drawable.ic_menu_agenda)
                        layoutParams = LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()).apply {
                            marginEnd = (12 * dp).toInt()
                        }
                    }
                    val folderNameText = TextView(ctx).apply {
                        id = android.R.id.text1
                        textSize = 16f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val chevron = TextView(ctx).apply {
                        id = android.R.id.text2
                        text = "›"
                        textSize = 20f
                        setTextColor(android.graphics.Color.GRAY)
                    }
                    row.addView(icon)
                    row.addView(folderNameText)
                    row.addView(chevron)
                    FolderViewHolder(row)
                }
                TYPE_ENTRY -> {
                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                        val outValue = android.util.TypedValue()
                        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                        setBackgroundResource(outValue.resourceId)
                    }
                    val name = TextView(ctx).apply {
                        id = android.R.id.text1
                        textSize = 16f
                    }
                    val url = TextView(ctx).apply {
                        id = android.R.id.text2
                        textSize = 12f
                        setTextColor(android.graphics.Color.GRAY)
                        setPadding(0, (4 * dp).toInt(), 0, 0)
                    }
                    row.addView(name)
                    row.addView(url)
                    EntryViewHolder(row)
                }
                else -> {
                    val text = TextView(ctx).apply {
                        textSize = 12f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(android.graphics.Color.GRAY)
                        setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    DateHeaderViewHolder(text)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is SettingsArchiveItem.FolderItem -> {
                    val vh = holder as FolderViewHolder
                    vh.textView.text = item.folder.name
                    vh.itemView.alpha = 1f
                    vh.itemView.setOnClickListener { onFolderClick(item.folder) }
                    vh.itemView.setOnLongClickListener {
                        onFolderLongPress(item.folder)
                        true
                    }
                }
                is SettingsArchiveItem.EntryItem -> {
                    val vh = holder as EntryViewHolder
                    vh.nameView.text = item.entry.name
                    vh.urlView.text = item.entry.url
                    vh.itemView.alpha = if (item.isLocked) 0.4f else 1f
                    if (item.isLocked) {
                        vh.itemView.setOnClickListener(null)
                        vh.itemView.setOnLongClickListener {
                            Toast.makeText(vh.itemView.context, "Lock-in active", Toast.LENGTH_SHORT).show()
                            true
                        }
                    } else {
                        vh.itemView.setOnClickListener { onEntryClick(item.entry) }
                        vh.itemView.setOnLongClickListener {
                            onEntryLongPress(item.entry)
                            true
                        }
                    }
                }
                is SettingsArchiveItem.DateHeader -> {
                    (holder as DateHeaderViewHolder).textView.text = item.label
                }
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private sealed class SettingsItem {
        data class FolderItem(val folder: Folder) : SettingsItem()
        data class EntryItem(val entry: WhitelistEntry) : SettingsItem()
    }

    private class WhitelistAdapter(
        items: List<SettingsItem>,
        private val onFolderClick: (Folder) -> Unit,
        private val onFolderLongPress: (Folder) -> Unit,
        private val onFolderDelete: (Folder) -> Unit,
        private val onEntryLongPress: (WhitelistEntry) -> Unit,
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
            val deleteButton: ImageButton = view.findViewById(android.R.id.button1)
            val arrowView: TextView = view.findViewById(android.R.id.summary)
            val emojiView: TextView? = view.findViewWithTag("emojiIcon")
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
                    if (item.folder.isCurated && item.folder.iconEmoji != null) {
                        vh.iconView.visibility = View.GONE
                        vh.emojiView?.visibility = View.VISIBLE
                        vh.emojiView?.text = item.folder.iconEmoji
                    } else {
                        vh.iconView.visibility = View.VISIBLE
                        vh.emojiView?.visibility = View.GONE
                    }
                    vh.itemView.setOnClickListener { onFolderClick(item.folder) }
                    vh.itemView.setOnLongClickListener {
                        onFolderLongPress(item.folder)
                        true
                    }
                    vh.deleteButton.setOnClickListener { onFolderDelete(item.folder) }
                }
                is SettingsItem.EntryItem -> {
                    val vh = holder as EntryViewHolder
                    vh.nameView.text = item.entry.name
                    vh.urlView.text = item.entry.url
                    vh.itemView.setOnLongClickListener {
                        onEntryLongPress(item.entry)
                        true
                    }
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
