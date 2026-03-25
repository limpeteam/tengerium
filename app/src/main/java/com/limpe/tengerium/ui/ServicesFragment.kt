package com.limpe.tengerium.ui

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.button.MaterialButton
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.databinding.FragmentServicesBinding
import com.limpe.tengerium.util.AnimationHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import uniffi.msnp11_sdk.Config

class ServicesFragment : Fragment() {

    private var _binding: FragmentServicesBinding? = null
    private val binding get() = _binding!!
    
    private val mainViewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory((requireActivity().application as TengeriumApp).repository)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val repository = (requireActivity().application as TengeriumApp).repository
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.config.collectLatest { config ->
                    updateUI(config)
                }
            }
        }
    }

    private fun updateUI(config: Config?) {
        binding.containerServices.removeAllViews()
        
        if (config == null || config.tabs.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }

        binding.tvEmpty.visibility = View.GONE
        
        config.tabs.forEachIndexed { index, tab ->
            val btn = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (64 * resources.displayMetrics.density).toInt()
                ).apply {
                    setMargins(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
                }
                text = tab.name
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding((16 * resources.displayMetrics.density).toInt(), 0, (16 * resources.displayMetrics.density).toInt(), 0)
                
                // Настройки иконки
                iconPadding = (16 * resources.displayMetrics.density).toInt()
                iconSize = (36 * resources.displayMetrics.density).toInt()
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                
                // Отключаем тинт сразу, чтобы не перекрывать цвета иконки
                iconTint = null

                // Сначала ставим плейсхолдер
                setIconResource(R.drawable.language_chinese_dayi_24)

                // Загружаем изображение службы из URL
                val imageUrl = tab.image.trim()
                if (imageUrl.isNotEmpty()) {
                    Log.d("ServicesFragment", "Loading image for ${tab.name}: $imageUrl")
                    
                    // Попробуем загрузить иконку. Если это HTTP, убедитесь, что cleartext traffic разрешен.
                    Glide.with(this@ServicesFragment)
                        .load(imageUrl)
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(object : CustomTarget<Drawable>() {
                            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                                Log.d("ServicesFragment", "Image successfully loaded for ${tab.name}")
                                icon = resource
                                iconTint = null // Повторно на всякий случай
                            }
                            override fun onLoadCleared(placeholder: Drawable?) {
                                icon = placeholder
                            }
                            override fun onLoadFailed(errorDrawable: Drawable?) {
                                Log.e("ServicesFragment", "Failed to load image for ${tab.name}: $imageUrl")
                                // Оставляем плейсхолдер или ставим error drawable
                                setIconResource(R.drawable.language_chinese_dayi_24)
                            }
                        })
                }

                setOnClickListener {
                    openService(tab.contentUrl, tab.name)
                }
            }
            binding.containerServices.addView(btn)
            
            // Анимируем появление каждой кнопки с небольшой задержкой (эффект каскада)
            AnimationHelper.animateServiceItemIn(btn, (index * 50).toLong())
        }
    }

    private fun openService(url: String, title: String) {
        val mainFragment = parentFragment as? MainFragment
        if (mainFragment != null && mainFragment.isTablet) {
            val webViewFragment = WebViewFragment.newInstance(url, title)
            mainFragment.showDetail(webViewFragment)
        } else {
            val bundle = bundleOf("url" to url, "title" to title)
            findNavController().navigate(R.id.action_MainFragment_to_WebViewFragment, bundle)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
