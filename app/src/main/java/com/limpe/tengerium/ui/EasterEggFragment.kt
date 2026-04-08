package com.limpe.tengerium.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.limpe.tengerium.R
import com.limpe.tengerium.databinding.FragmentEasterEggBinding

class EasterEggFragment : Fragment() {

    private var _binding: FragmentEasterEggBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEasterEggBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            performBack()
        }
        
        binding.swipeBackLayout.setOnSwipeBackListener {
            performBack()
        }

        val context = requireContext()
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0"
        }

        // Устанавливаем заголовок в тулбар
        binding.toolbar.title = getString(R.string.easter_egg_title, versionName)
        
        // Источник
        binding.tvSource.text = getString(R.string.easter_egg_source, "onashem.mediasole.ru")
        
        // Основной текст
        binding.tvContent.text = getString(R.string.easter_egg_content)

        // Анимация появления контента
        binding.layoutContent.alpha = 0f
        binding.layoutContent.translationY = 50f
        binding.layoutContent.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(600)
            .start()
    }

    private fun performBack() {
        val mainFragment = findMainFragment()
        if (mainFragment != null) {
            mainFragment.closeDetail()
        } else {
            findNavController().popBackStack()
        }
    }

    private fun findMainFragment(): MainFragment? {
        return generateSequence(parentFragment) { it.parentFragment }
            .filterIsInstance<MainFragment>()
            .firstOrNull()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
