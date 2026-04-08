package com.limpe.tengerium.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.checkbox.MaterialCheckBox
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.databinding.FragmentMemoryUsageBinding
import com.limpe.tengerium.util.AnimationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MemoryUsageFragment : Fragment() {

    private var _binding: FragmentMemoryUsageBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory((requireActivity().application as TengeriumApp).repository)
    }

    private val categories = mutableListOf<CacheCategory>()

    data class CacheCategory(
        val id: String,
        val nameRes: Int,
        val colorRes: Int,
        var size: Long = 0,
        var isChecked: Boolean = true
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMemoryUsageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { performBack() }
        binding.swipeBackLayout.setOnSwipeBackListener { performBack() }

        initCategories()
        refreshData()

        binding.btnClear.setOnClickListener {
            showClearConfirmation()
        }
    }

    private fun initCategories() {
        categories.clear()
        categories.add(CacheCategory("profiles", R.string.storage_profiles, R.color.cache_profiles))
        categories.add(CacheCategory("messages", R.string.storage_messages, R.color.cache_messages))
        categories.add(CacheCategory("contacts", R.string.storage_contacts, R.color.cache_contacts))
    }

    private fun refreshData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            
            val totalCache = withContext(Dispatchers.IO) {
                val avatarsDir = File(context.cacheDir, "avatars")
                val totalAvatarsSize = getFolderSize(avatarsDir)

                val dbFile = context.getDatabasePath("tengerium_secure.db")
                val dbTotalSize = if (dbFile.exists()) {
                    dbFile.length() + 
                    File(dbFile.path + "-wal").let { if (it.exists()) it.length() else 0L } + 
                    File(dbFile.path + "-shm").let { if (it.exists()) it.length() else 0L }
                } else 0L
                
                // Проверяем количество сообщений для ТЕКУЩЕГО аккаунта
                val messageCount = viewModel.getMessageCount()
                
                categories.forEach { category ->
                    category.size = when (category.id) {
                        "profiles" -> totalAvatarsSize
                        "messages" -> if (messageCount == 0L) 0L else (dbTotalSize * 0.85).toLong() 
                        "contacts" -> if (messageCount == 0L) 0L else (dbTotalSize * 0.15).toLong().coerceAtLeast(1024L * 10)
                        else -> 0L
                    }
                }
                categories.sumOf { it.size }
            }

            updateUI(totalCache)
        }
    }

    private fun updateUI(totalCache: Long) {
        val hasCache = totalCache > 1024
        
        // Плавное переключение между диаграммой и иконкой "пусто"
        if (hasCache) {
            binding.pieChart.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400).start()
            binding.layoutSizeText.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(400).start()
            binding.ivNoCache.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(400).start()
            
            binding.layoutCategories.visibility = View.VISIBLE
            binding.layoutBottomAction.visibility = View.VISIBLE
            binding.tvChartTitle.text = getString(R.string.memory_usage)
        } else {
            binding.pieChart.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(400).start()
            binding.layoutSizeText.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(400).start()
            binding.ivNoCache.animate().alpha(0.2f).scaleX(1f).scaleY(1f).setDuration(400).start()
            
            binding.layoutCategories.visibility = View.GONE
            binding.layoutBottomAction.visibility = View.GONE
            binding.tvChartTitle.text = getString(R.string.no_cache_message)
        }

        val (valueStr, unit) = formatSizeParts(totalCache)
        val valueInt = try { 
            if (valueStr.contains(".")) valueStr.substringBefore(".").toInt() 
            else valueStr.toInt() 
        } catch(e: Exception) { 0 }
        
        AnimationHelper.animateTextValue(binding.tvTotalCacheValue, 0, valueInt)
        binding.tvTotalCacheUnit.text = unit
        
        if (hasCache) {
            updateChart(animate = true)
        }

        val appsCacheDir = requireContext().cacheDir.parentFile?.parentFile
        val totalAppsCache = getFolderSize(appsCacheDir ?: requireContext().cacheDir)
        val usagePercent = if (totalAppsCache > 0) (totalCache * 100 / totalAppsCache).toInt().coerceIn(1, 100) else 0
        
        binding.tvTengeriumUsagePercent.text = if (hasCache) {
            getString(R.string.tengerium_usage_format, usagePercent)
        } else {
            ""
        }

        binding.layoutCategories.removeAllViews()
        if (hasCache) {
            categories.forEach { category ->
                val itemView = layoutInflater.inflate(R.layout.item_cache_category, binding.layoutCategories, false)
                val checkBox = itemView.findViewById<MaterialCheckBox>(R.id.checkBox)
                val tvName = itemView.findViewById<TextView>(R.id.tvName)
                val tvSize = itemView.findViewById<TextView>(R.id.tvSize)
                val tvPercent = itemView.findViewById<TextView>(R.id.tvPercent)

                tvName.text = getString(category.nameRes)
                tvSize.text = formatSize(category.size)
                
                val percentFloat = if (totalCache > 0) (category.size.toFloat() / totalCache.toFloat() * 100f) else 0f
                tvPercent.text = if (percentFloat > 0f && percentFloat < 1f) "<1%" else "${percentFloat.toInt()}%"
                
                checkBox.isChecked = category.isChecked
                checkBox.buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), category.colorRes))
                
                checkBox.setOnCheckedChangeListener { _, isChecked ->
                    category.isChecked = isChecked
                    updateChart(animate = true)
                }

                binding.layoutCategories.addView(itemView)
            }
        }
    }

    private fun updateChart(animate: Boolean) {
        val selectedItems = categories.filter { it.isChecked }
        val selectedTotal = selectedItems.sumOf { it.size }
        
        val slices = categories.map { category ->
            val percentage = if (selectedTotal > 0 && category.isChecked) {
                (category.size.toFloat() / selectedTotal.toFloat() * 100f)
            } else 0f
            CirclePieChartView.PieSlice(category.id, percentage, category.colorRes)
        }
        binding.pieChart.setData(slices, animate)
    }

    private fun showClearConfirmation() {
        val selectedCount = categories.count { it.isChecked }
        if (selectedCount == 0) return

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.clear_history_title)
            .setMessage(R.string.clear_history_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                performClear()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun performClear() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            withContext(Dispatchers.IO) {
                categories.forEach { category ->
                    if (category.isChecked) {
                        when (category.id) {
                            "profiles" -> {
                                deleteFolderContents(File(context.cacheDir, "avatars"))
                            }
                            "messages" -> viewModel.clearHistory()
                        }
                    }
                }
            }
            refreshData()
            Toast.makeText(requireContext(), R.string.cache_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatSize(size: Long): String {
        return when {
            size >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", size.toFloat() / (1024 * 1024))
            size >= 1024 -> String.format(Locale.ROOT, "%.1f KB", size.toFloat() / 1024)
            else -> "$size B"
        }
    }

    private fun formatSizeParts(size: Long): Pair<String, String> {
        return when {
            size >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f", size.toFloat() / (1024 * 1024)) to "MB"
            size >= 1024 -> String.format(Locale.ROOT, "%.1f", size.toFloat() / 1024) to "KB"
            else -> size.toString() to "B"
        }
    }

    private fun getFolderSize(file: File): Long {
        var size: Long = 0
        if (file.exists() && file.isDirectory) {
            file.listFiles()?.forEach { size += getFolderSize(it) }
        } else if (file.exists()) {
            size = file.length()
        }
        return size
    }

    private fun deleteFolderContents(file: File) {
        if (file.exists() && file.isDirectory) {
            file.listFiles()?.forEach { 
                if (it.isDirectory) deleteFolderContents(it)
                it.delete()
            }
        }
    }

    private fun performBack() {
        val mainFragment = findMainFragment()
        if (mainFragment != null) {
            mainFragment.closeDetail()
        } else {
            try {
                findNavController().popBackStack()
            } catch (e: Exception) {
                activity?.finish()
            }
        }
    }

    private fun findMainFragment(): MainFragment? {
        var parent = parentFragment
        while (parent != null) {
            if (parent is MainFragment) return parent
            parent = parent.parentFragment
        }
        return null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
