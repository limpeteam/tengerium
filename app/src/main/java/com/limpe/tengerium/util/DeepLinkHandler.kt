package com.limpe.tengerium.util

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.limpe.tengerium.R
import com.limpe.tengerium.data.security.SecurePrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DeepLinkHandler(
    private val activity: AppCompatActivity,
    private val securePrefs: SecurePrefs
) {

    fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return

        val data: Uri? = intent.data
        if (data != null && data.scheme == "tengerium") {
            val server = data.getQueryParameter("server")
            val nexus = data.getQueryParameter("nexus")
            val config = data.getQueryParameter("config")

            if (server != null && nexus != null) {
                showDeepLinkDialog(server, nexus, config)
            }
        }
    }

    private fun showDeepLinkDialog(server: String, nexus: String, config: String?) {
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_deep_link_warning, null)
        val builder = AlertDialog.Builder(activity)
            .setView(dialogView)
            .setCancelable(false)

        val dialog = builder.create()
        dialog.show()

        val tvMessage = dialogView.findViewById<TextView>(R.id.tvWarningMessage)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSave)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        tvMessage.text = activity.getString(R.string.deep_link_warning_message, server, nexus)

        btnSave.isEnabled = false
        btnCancel.setOnClickListener { dialog.dismiss() }

        activity.lifecycleScope.launch {
            for (i in 10 downTo 1) {
                btnSave.text = activity.getString(R.string.deep_link_wait_format, i)
                delay(1000)
            }
            btnSave.text = activity.getString(R.string.add)
            btnSave.isEnabled = true
            btnSave.setOnClickListener {
                securePrefs.serverAddress = server
                securePrefs.nexusDomain = nexus
                if (config != null) {
                    securePrefs.configUrl = config
                }
                Toast.makeText(activity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
    }
}
