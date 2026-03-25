package com.limpe.tengerium.ui

import android.content.Context
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.limpe.tengerium.R
import com.limpe.tengerium.data.AppConfig
import java.io.File

object AvatarUtils {

    fun getAppLogoResId(context: Context): Int {
        val branch = AppConfig.getBranch(context)
        return when (branch.lowercase()) {
            "canary" -> R.drawable.canary_tengerium_logo_n26
            "beta", "preview" -> R.drawable.beta_tengerium_logo_n26
            else -> R.drawable.tengerium_logo_n26
        }
    }

    fun loadAvatar(imageView: ImageView, url: String?, account: String?, isGroup: Boolean = false, onDone: (() -> Unit)? = null) {
        val defaultRes = if (isGroup) R.drawable.group_24 else R.drawable.ic_default_avatar
        
        if (!AppConfig.enableAvatars) {
            imageView.setImageResource(defaultRes)
            onDone?.invoke()
            return
        }

        if (url.isNullOrEmpty()) {
            imageView.setImageResource(defaultRes)
            onDone?.invoke()
            return
        }

        val requestManager = Glide.with(imageView.context)
        val requestBuilder = when {
            url.startsWith("base64,") -> {
                try {
                    val base64String = url.substring(7)
                    val bytes = android.util.Base64.decode(base64String, android.util.Base64.DEFAULT)
                    requestManager.load(bytes)
                } catch (e: Exception) {
                    requestManager.load(defaultRes)
                }
            }
            url.startsWith("/") -> requestManager.load(File(url))
            else -> requestManager.load(url)
        }

        requestBuilder
            .placeholder(defaultRes)
            .error(defaultRes)
            .fallback(defaultRes)
            .circleCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(imageView)
            
        onDone?.invoke()
    }
}
