package com.limpe.tengerium.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.limpe.tengerium.R
import com.limpe.tengerium.data.AppConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

object AvatarUtils {

    fun getAppLogoResId(context: Context): Int {
        val branch = AppConfig.getBranch(context)
        return when (branch.lowercase()) {
            "canary" -> R.drawable.canary_tengerium_logo_n26
            "beta", "preview" -> R.drawable.beta_tengerium_logo_n26
            else -> R.drawable.tengerium_logo_n26
        }
    }

    /**
     * Подготавливает изображение для использования в качестве аватара MSNP.
     * Делает Center Crop и уменьшает до 96x96 в формате JPEG.
     */
    fun prepareAvatar(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val original = BitmapFactory.decodeStream(inputStream) ?: return null
                processBitmapToAvatar(original)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun prepareAvatarFromFile(file: File): ByteArray? {
        return try {
            if (!file.exists()) return null
            FileInputStream(file).use { inputStream ->
                val original = BitmapFactory.decodeStream(inputStream) ?: return null
                processBitmapToAvatar(original)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun prepareAvatarFromBitmap(bitmap: Bitmap): ByteArray? {
        return try {
            processBitmapToAvatar(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun prepareAvatarFromRes(context: Context, resId: Int): ByteArray? {
        return try {
            val original = BitmapFactory.decodeResource(context.resources, resId) ?: return null
            processBitmapToAvatar(original)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun processBitmapToAvatar(original: Bitmap): ByteArray {
        // Center Crop logic
        val size = minOf(original.width, original.height)
        val x = (original.width - size) / 2
        val y = (original.height - size) / 2
        
        val cropped = if (original.width == original.height) original 
                      else Bitmap.createBitmap(original, x, y, size, size)
        
        // MSN стандарт — 96x96. 
        val resized = if (cropped.width == 96 && cropped.height == 96) cropped
                      else Bitmap.createScaledBitmap(cropped, 96, 96, true)

        val out = ByteArrayOutputStream()
        
        // ВАЖНО: MSNP11 ожидает JPEG для аватаров (MsnObject)
        resized.compress(Bitmap.CompressFormat.JPEG, 90, out)
        
        if (original != cropped && original != resized) original.recycle()
        if (cropped != resized && cropped != original) cropped.recycle()
        
        return out.toByteArray()
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
