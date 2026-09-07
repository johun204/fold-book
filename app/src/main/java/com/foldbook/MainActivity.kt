package com.foldbook

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    private val requestLegacyPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    private val openImage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = res.data?.data ?: return@registerForActivityResult
        val path = resolveToFilePath(uri)
        if (path != null && File(path).exists()) {
            startActivity(
                Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_PATH, path)
            )
        } else {
            Toast.makeText(this, R.string.err_path, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.openBtn).setOnClickListener { onOpenClicked() }
        findViewById<Button>(R.id.smbBtn).setOnClickListener {
            startActivity(Intent(this, SmbConnectActivity::class.java))
        }
        findViewById<Button>(R.id.driveBtn).setOnClickListener {
            startActivity(Intent(this, DriveBrowseActivity::class.java))
        }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        status.setText(if (hasStorageAccess()) R.string.status_ready else R.string.status_need_perm)
    }

    private fun onOpenClicked() {
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        openImage.launch(i)
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        } else {
            requestLegacyPerm.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
