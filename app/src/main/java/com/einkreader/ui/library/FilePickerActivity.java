package com.einkreader.ui.library;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.TextView;
import android.widget.Toast;

public class FilePickerActivity extends Activity {

    private static final int REQUEST_OPEN_FILE = 1001;
    private static final int REQUEST_MANAGE_STORAGE = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("正在打开文件选择器...");
        tv.setTextSize(18);
        tv.setPadding(40, 80, 40, 40);
        tv.setTextColor(0xFF000000);
        setContentView(tv);

        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                openFilePicker();
            } else {
                Toast.makeText(this, "需要授予文件管理权限", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
            }
        } else {
            openFilePicker();
        }
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "application/epub+zip"});
        startActivityForResult(intent, REQUEST_OPEN_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                Intent result = new Intent();
                result.putExtra("file_uri", uri.toString());
                String filePath = getPathFromUri(uri);
                if (filePath != null) {
                    result.putExtra("file_path", filePath);
                } else {
                    result.putExtra("file_path", uri.toString());
                }
                setResult(RESULT_OK, result);
                finish();
                return;
            }
        } else if (requestCode == REQUEST_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
                openFilePicker();
            } else {
                Toast.makeText(this, "需要文件管理权限", Toast.LENGTH_LONG).show();
                finish();
            }
            return;
        }
        Toast.makeText(this, "未选择文件", Toast.LENGTH_SHORT).show();
        finish();
    }

    private String getPathFromUri(Uri uri) {
        if (uri == null) return null;
        if ("file".equals(uri.getScheme())) {
            return uri.getPath();
        }
        try {
            String path = uri.getPath();
            if (path != null && path.contains(":") && path.contains("/")) {
                String[] parts = path.split(":");
                if (parts.length >= 2) {
                    String realPath = parts[1];
                    int idx = realPath.indexOf("/");
                    if (idx >= 0) {
                        return realPath.substring(idx);
                    }
                }
            }
        } catch (Exception e) { }
        return null;
    }
}