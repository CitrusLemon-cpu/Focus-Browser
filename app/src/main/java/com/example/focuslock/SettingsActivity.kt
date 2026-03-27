package com.example.focuslock

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
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
            WhitelistManager.getWhitelist(this).toMutableList()
        ) { entry ->
            WhitelistManager.removeEntry(this, entry)
            refreshList()
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnAdd.setOnClickListener { showAddEntryDialog() }
        binding.btnChangePassword.setOnClickListener { showChangePasswordFlow() }
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
        val input = EditText(this).apply {
            hint = "e.g. google.com or google.com*"
            setPadding(48, 32, 48, 0)
        }

        AlertDialog.Builder(this)
            .setTitle("Add Whitelist Entry")
            .setView(input)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val entry = input.text.toString().trim()

                        if (entry.isEmpty()) {
                            input.error = "Entry cannot be empty"
                            return@setOnClickListener
                        }

                        val existing = WhitelistManager.getWhitelist(this@SettingsActivity)
                        if (existing.contains(entry)) {
                            input.error = "Entry already exists"
                            return@setOnClickListener
                        }

                        WhitelistManager.addEntry(this@SettingsActivity, entry)
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
        private var items: MutableList<String>,
        private val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<WhitelistAdapter.ViewHolder>() {

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
            holder.deleteButton.setOnClickListener { onDelete(entry) }
        }

        override fun getItemCount(): Int = items.size

        fun updateList(newItems: MutableList<String>) {
            items = newItems
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }
    }
}
