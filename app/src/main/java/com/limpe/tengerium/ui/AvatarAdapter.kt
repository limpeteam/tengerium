package com.limpe.tengerium.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.limpe.tengerium.R
import com.limpe.tengerium.data.db.AvatarEntity
import com.limpe.tengerium.databinding.ItemAvatarBinding
import java.io.File

class AvatarAdapter(
    private val onAvatarClick: (AvatarEntity) -> Unit,
    private val onAddClick: () -> Unit
) : ListAdapter<AvatarAdapter.AvatarItem, RecyclerView.ViewHolder>(DiffCallback) {

    sealed class AvatarItem {
        data class Avatar(val entity: AvatarEntity) : AvatarItem()
        object AddButton : AvatarItem()
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is AvatarItem.Avatar -> VIEW_TYPE_AVATAR
            is AvatarItem.AddButton -> VIEW_TYPE_ADD
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_AVATAR) {
            AvatarViewHolder(ItemAvatarBinding.inflate(inflater, parent, false))
        } else {
            AddViewHolder(inflater.inflate(R.layout.item_avatar_add, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        if (holder is AvatarViewHolder && item is AvatarItem.Avatar) {
            holder.bind(item.entity)
            setAnimation(holder.itemView, position)
        } else if (holder is AddViewHolder) {
            holder.itemView.setOnClickListener { onAddClick() }
            setAnimation(holder.itemView, position)
        }
    }

    private var lastPosition = -1
    private fun setAnimation(viewToAnimate: View, position: Int) {
        if (position > lastPosition) {
            val animation = AnimationUtils.loadAnimation(viewToAnimate.context, android.R.anim.fade_in)
            animation.duration = 300
            viewToAnimate.startAnimation(animation)
            lastPosition = position
        }
    }

    inner class AvatarViewHolder(private val binding: ItemAvatarBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entity: AvatarEntity) {
            val context = itemView.context
            if (entity.isStandard) {
                val resId = context.resources.getIdentifier(entity.path, "drawable", context.packageName)
                binding.ivAvatarItem.setImageResource(resId)
            } else {
                Glide.with(context)
                    .load(entity.path?.let { File(it) })
                    .placeholder(R.drawable.ic_default_avatar)
                    .centerCrop()
                    .into(binding.ivAvatarItem)
            }
            binding.root.setOnClickListener { onAvatarClick(entity) }
        }
    }

    class AddViewHolder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        private const val VIEW_TYPE_AVATAR = 1
        private const val VIEW_TYPE_ADD = 2

        private val DiffCallback = object : DiffUtil.ItemCallback<AvatarItem>() {
            override fun areItemsTheSame(oldItem: AvatarItem, newItem: AvatarItem): Boolean {
                if (oldItem is AvatarItem.Avatar && newItem is AvatarItem.Avatar) {
                    return oldItem.entity.sha1 == newItem.entity.sha1
                }
                return oldItem == newItem
            }

            override fun areContentsTheSame(oldItem: AvatarItem, newItem: AvatarItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
