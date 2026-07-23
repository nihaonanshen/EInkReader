package com.einkreader.ui.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast

class FilePickerActivity : Activity() {

    private companion object {
        const val REQUEST_OPEN_FILE = 1001
        const val REQUEST_MANAGE_STORAGE = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "正在打开文件选择器..."
            setTextSize(18f)
            setPadding(40, 80, 40, 40)
            setTextColor(0xFF000000.toInt())
        }
        setContentView(tv)

        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            openFilePicker()
        } else if (Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, "需要授予文件管理权限", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
        } else {
            openFilePicker()
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/epub+zip"))
        }
        startActivityForResult(intent, REQUEST_OPEN_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when {
            requestCode == REQUEST_OPEN_FILE && resultCode == RESULT_OK && data != null -> {
                val uri = data.data ?: return finish()
                val result = Intent().apply {
                    putExtra("file_uri", uri.toString())
                    putExtra("file_path", getPathFromUri(uri) ?: uri.toString())
                    setResult(RESULT_OK, this@apply)
                }
                finish()
            }
            requestCode == REQUEST_MANAGE_STORAGE && Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager() -> {
                openFilePicker()
            }
            requestCode == REQUEST_MANAGE_STORAGE -> {
                Toast.makeText(this, "需要文件管理权限", Toast.LENGTH_SHORT).show()
                finish()
            }
            else -> {
                Toast.makeText(this, "未选择文件", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        if (uri.scheme != "file") {
            try {
                val path = uri.path
                if (path != null && path.contains(":") && path.contains("/")) {
                    val parts = path.split(":")
                    if (parts.size >= 2) {
                        val realPath = parts[1]
                        val idx = realPath.indexOf('/')
                        if (idx >= 0) return realPath.substring(idx)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("FilePicker", "getPathFromUri failed", e)
            }
        }
        return uri.path
    }
}
