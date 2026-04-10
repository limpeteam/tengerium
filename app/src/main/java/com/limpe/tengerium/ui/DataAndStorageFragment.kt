package com.limpe.tengerium.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.limpe.tengerium.R
import com.limpe.tengerium.databinding.FragmentDataAndStorageBinding

class DataAndStorageFragment : Fragment() {

    private var _binding: FragmentDataAndStorageBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDataAndStorageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { performBack() }
        
        binding.swipeBackLayout.setOnSwipeBackListener {
            performBack()
        }

        binding.btnMemoryUsage.setOnClickListener {
            navigateToDetail(MemoryUsageFragment())
        }
    }

    private fun navigateToDetail(fragment: Fragment) {
        val mainFragment = findMainFragment()
        if (mainFragment != null) {
            mainFragment.showDetail(fragment, addToBackStack = true)
        } else {
            // Фолбэк, если по какой-то причине мы не внутри MainFragment
            parentFragmentManager.beginTransaction()
                .replace(R.id.detail_container, fragment)
                .addToBackStack(null)
                .commit()
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
