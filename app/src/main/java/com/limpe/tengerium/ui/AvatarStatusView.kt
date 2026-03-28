package com.limpe.tengerium.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import com.limpe.tengerium.R
import com.limpe.tengerium.databinding.LayoutAvatarWithStatusBinding

class AvatarStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding: LayoutAvatarWithStatusBinding

    init {
        binding = LayoutAvatarWithStatusBinding.inflate(LayoutInflater.from(context), this)
        
        attrs?.let {
            val typedArray = context.obtainStyledAttributes(it, R.styleable.AvatarStatusView)
            val avatarSize = typedArray.getDimensionPixelSize(R.styleable.AvatarStatusView_avatarSize, -1)
            if (avatarSize != -1) {
                binding.ivAvatar.layoutParams.width = avatarSize
                binding.ivAvatar.layoutParams.height = avatarSize
            }
            typedArray.recycle()
        }
    }

    fun setAvatar(url: String?, account: String?, isGroup: Boolean = false) {
        AvatarUtils.loadAvatar(binding.ivAvatar, url, account, isGroup)
    }

    fun setStatus(status: String?) {
        val color = StatusUtils.getStatusColor(status)
        setStatusColor(color)
    }
    
    fun setStatusColor(color: Int) {
        binding.viewStatus.background?.setTint(color)
    }

    val avatarView get() = binding.ivAvatar
    val statusView get() = binding.viewStatus
    val statusBorderView get() = binding.viewStatusBorder
}
