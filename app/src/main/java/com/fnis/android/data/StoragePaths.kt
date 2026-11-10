package com.fnis.android.data

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

/**
 * The Storage Access Framework only hands out content:// URIs, but the FNIS
 * data files (and the game itself) need real filesystem paths.
 *
 * The system ExternalStorageProvider encodes those paths in the document ID,
 * e.g. "primary:Games/Skyrim/Data". This helper maps those URIs back to Files.
 */
object StoragePaths {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun resolve(context: Context, uri: Uri): File? {
        return when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            "content" -> resolveDocumentUri(uri)
            else -> null
        }
    }

    private fun resolveDocumentUri(uri: Uri): File? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null

        val documentId = try {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
        } catch (_: Exception) {
            return null
        }

        val separator = documentId.indexOf(':')
        val volume: String
        val relativePath: String
        if (separator >= 0) {
            volume = documentId.substring(0, separator)
            relativePath = documentId.substring(separator + 1)
        } else {
            volume = documentId
            relativePath = ""
        }

        val base = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$volume")
        }

        return if (relativePath.isBlank()) base else File(base, relativePath)
    }

    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestAllFilesAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            try {
                activity.startActivity(appIntent)
                return
            } catch (_: Exception) {
                // Fall through to the global screen.
            }
            activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                2001
            )
        }
    }
}
