package com.limpe.tengerium.util

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.limpe.tengerium.R
import com.limpe.tengerium.data.security.SecurePrefs

object ConnectionDialogHelper {
    
    /**
     * Показывает диалог настройки сервера и домена.
     * Параметр Config URL является необязательным.
     */
    fun showConnectionDialog(context: Context, securePrefs: SecurePrefs) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val etHost = EditText(context).apply {
            hint = context.getString(R.string.host_hint)
            setText(securePrefs.serverAddress)
            setSingleLine(true)
        }

        val etNexusDomain = EditText(context).apply {
            hint = context.getString(R.string.nexus_hint)
            setText(securePrefs.nexusDomain)
            setSingleLine(true)
        }

        val etConfigUrl = EditText(context).apply {
            hint = "Config URL (Optional)"
            setText(securePrefs.configUrl)
            setSingleLine(true)
        }

        layout.addView(etHost)
        layout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 16) }) 
        layout.addView(etNexusDomain)
        layout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 16) })
        layout.addView(etConfigUrl)

        AlertDialog.Builder(context)
            .setTitle(R.string.connection_settings)
            .setView(layout)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val host = etHost.text.toString().trim()
                val nexus = etNexusDomain.text.toString().trim()
                val config = etConfigUrl.text.toString().trim()

                // Проверяем только обязательные поля
                if (host.isNotEmpty() && nexus.isNotEmpty()) {
                    securePrefs.serverAddress = host
                    securePrefs.nexusDomain = nexus
                    securePrefs.configUrl = config
                    Toast.makeText(context, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Server and Nexus are required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
