package com.limpe.tengerium.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.limpe.tengerium.R
import com.limpe.tengerium.data.AppConfig
import com.limpe.tengerium.databinding.FragmentAboutBinding

class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
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

        setupAppInfo()
        setupLinks()
        
        // Клик по логотипу - проверка обновлений
        binding.ivAppLogo.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_AboutFragment_to_UpdateFragment)
            } catch (e: Exception) {}
        }

        // Пасхалка на долгое нажатие всей верхней части
        val easterEggTrigger = View.OnLongClickListener {
            navigateToEasterEgg()
            true
        }

        binding.layoutAppHeader.setOnLongClickListener(easterEggTrigger)
        
        // Telegram link
        binding.btnTelegram.setOnClickListener {
            openUrl("https://t.me/tengerium")
        }

        // GitHub link
        binding.btnGitHub.setOnClickListener {
            openUrl("https://github.com/lednikofff/tengerium")
        }

        // Discord link
        binding.btnDiscord.setOnClickListener {
            openUrl("https://discord.gg/y6frBntsC5")
        }
    }

    private fun navigateToEasterEgg() {
        val mainFragment = findMainFragment()
        if (mainFragment != null) {
            mainFragment.showDetail(EasterEggFragment(), addToBackStack = true)
        } else {
            try {
                findNavController().navigate(R.id.EasterEggFragment)
            } catch (e: Exception) {}
        }
    }

    private fun performBack() {
        val mainFragment = findMainFragment()
        if (mainFragment != null) {
            mainFragment.closeDetail()
        } else {
            findNavController().popBackStack()
        }
    }

    private fun setupAppInfo() {
        val context = requireContext()
        val codeName = AppConfig.getCodeName(context)
        val branch = AppConfig.getBranch(context)
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "0.0.1"
        }

        binding.tvVersionDetails.text = getString(R.string.app_version_details_format, codeName, branch, versionName)

        if (AppConfig.LIMPE_EXP) {
            binding.tvLimpeSupport.visibility = View.VISIBLE
            binding.ivLimpeLogo.visibility = View.VISIBLE
        } else {
            binding.tvLimpeSupport.visibility = View.GONE
            binding.ivLimpeLogo.visibility = View.GONE
        }
    }

    private fun setupLinks() {
        binding.tvLibLink.setOnClickListener {
            openUrl("https://github.com/campos02/msnp11-sdk/tree/main/msnp11-sdk")
        }

        binding.tvDeveloper.setOnClickListener {
            openUrl("https://github.com/lednikofff")
        }

        binding.tvLimpeSupport.setOnClickListener {
            openUrl("https://github.com/limpetech")
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback
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
