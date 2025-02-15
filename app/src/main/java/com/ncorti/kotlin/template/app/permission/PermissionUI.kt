package com.ncorti.kotlin.template.app.permission

import android.app.AlertDialog
import android.content.Context
import com.ncorti.kotlin.template.app.R

object PermissionUI {
    fun showPermissionRationaleDialog(
        context: Context,
        onRequestPermission: () -> Unit,
        onCancel: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.storage_permission_rationale)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                onRequestPermission()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                onCancel()
            }
            .setCancelable(false)
            .show()
    }

    fun showPermissionPermanentlyDeniedDialog(
        context: Context,
        onOpenSettings: () -> Unit,
        onCancel: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.permission_permanently_denied)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                onOpenSettings()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                onCancel()
            }
            .setCancelable(false)
            .show()
    }
}
