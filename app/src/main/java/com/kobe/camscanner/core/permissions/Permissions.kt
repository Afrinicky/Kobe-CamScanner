package com.kobe.camscanner.core.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Kobe asks for exactly two things and never more (SDS 47): the camera, and - only below API 33 -
 * read access so gallery import works. On API 33+ the photo picker supplies images without any
 * permission at all.
 */
object KobePermissions {

    const val CAMERA = Manifest.permission.CAMERA

    /** Null on API 33+, where the photo picker needs no permission. */
    val galleryRead: String? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> null
        else -> Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasCamera(context: Context): Boolean = isGranted(context, CAMERA)

    fun appSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Camera permission state plus a request trigger, in one object the viewfinder can hold. */
class CameraPermissionState internal constructor(
    val granted: Boolean,
    val shouldExplain: Boolean,
    val request: () -> Unit,
)

@Composable
fun rememberCameraPermissionState(
    onGranted: () -> Unit = {},
): CameraPermissionState {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(KobePermissions.hasCamera(context)) }
    var denied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { result ->
        granted = result
        denied = !result
        if (result) onGranted()
    }

    return CameraPermissionState(
        granted = granted,
        shouldExplain = denied,
        request = { launcher.launch(KobePermissions.CAMERA) },
    )
}
