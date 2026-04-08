package com.limpe.tengerium.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.tabs.TabLayout
import com.limpe.tengerium.databinding.FragmentTrafficUsageBinding

class TrafficUsageFragment : Fragment() {

    private var _binding: FragmentTrafficUsageBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTrafficUsageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { performBack() }
        binding.swipeBackLayout.setOnSwipeBackListener { performBack() }

        setupTabs()
        updateTrafficStats(0) // Default to "All"
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                updateTrafficStats(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun updateTrafficStats(position: Int) {
        // Simulated stats for now
        val (sent, received) = when (position) {
            0 -> "4.2 MB" to "28.5 MB" // All
            1 -> "1.1 MB" to "5.2 MB"  // Mobile
            2 -> "3.1 MB" to "23.3 MB" // Wi-Fi
            3 -> "0 B" to "0 B"        // Roaming
            else -> "0 B" to "0 B"
        }
        
        binding.tvSentValue.text = sent
        binding.tvReceivedValue.text = received
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
