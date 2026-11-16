package io.github.km20197.limpadroid.data.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

interface PermissionChecker {
  fun hasAllFilesAccess(): Boolean
  fun hasNotificationPermission(): Boolean
  fun createAllFilesAccessIntent(): Intent
}

class AndroidPermissionChecker(
  private val context: Context,
) : PermissionChecker {

  override fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

  override fun hasNotificationPermission(): Boolean =
    ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

  override fun createAllFilesAccessIntent(): Intent {
    val packageUri = Uri.fromParts("package", context.packageName, null)
    val appSpecificIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
    return if (appSpecificIntent.resolveActivity(context.packageManager) != null) {
      appSpecificIntent
    } else {
      Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }
  }
}
