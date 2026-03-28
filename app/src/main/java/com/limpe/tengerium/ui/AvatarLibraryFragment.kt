package com.limpe.tengerium.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.canhub.cropper.CropImageView
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.AppConfig
import com.limpe.tengerium.data.db.AvatarEntity
import com.limpe.tengerium.databinding.FragmentAvatarLibraryBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AvatarLibraryFragment : Fragment() {

    private var _binding: FragmentAvatarLibraryBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: AvatarAdapter
    private lateinit var repository: com.limpe.tengerium.data.MSNPRepository

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { startCropMode(it) }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitCropMode()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAvatarLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = (requireActivity().application as TengeriumApp).repository

        if (!AppConfig.enableAvatarLibrary) {
            findNavController().popBackStack()
            return
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        setupRecyclerView()
        observeAvatars()

        binding.toolbar.setNavigationOnClickListener {
            if (binding.layoutCrop.isVisible) {
                exitCropMode()
            } else {
                findNavController().popBackStack()
            }
        }

        binding.btnDoneCrop.setOnClickListener {
            val croppedBitmap = binding.cropImageView.getCroppedImage()
            if (croppedBitmap != null) {
                processCroppedBitmap(croppedBitmap)
            } else {
                Toast.makeText(requireContext(), "Crop failed", Toast.LENGTH_SHORT).show()
            }
        }
        
        setupCropImageView()
    }

    private fun setupCropImageView() {
        binding.cropImageView.apply {
            setAspectRatio(1, 1)
            setFixedAspectRatio(true)
            guidelines = CropImageView.Guidelines.ON
            cropShape = CropImageView.CropShape.RECTANGLE
        }
    }

    private fun setupRecyclerView() {
        adapter = AvatarAdapter(
            onAvatarClick = { selectAvatar(it) },
            onAddClick = { pickImage.launch("image/*") }
        )
        binding.rvAvatars.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvAvatars.adapter = adapter
    }

    private fun observeAvatars() {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.avatarLibraryManager.allAvatars.collectLatest { entities ->
                val items = mutableListOf<AvatarAdapter.AvatarItem>()
                items.add(AvatarAdapter.AvatarItem.AddButton)
                items.addAll(entities.map { AvatarAdapter.AvatarItem.Avatar(it) })
                adapter.submitList(items)
            }
        }
    }

    private fun selectAvatar(avatar: AvatarEntity) {
        viewLifecycleOwner.lifecycleScope.launch {
            val path = if (avatar.isStandard) "std_${avatar.path}" else avatar.path
            if (path != null) {
                repository.updateAvatar(path)
                repository.avatarLibraryManager.updateLastUsed(avatar.sha1)
                Toast.makeText(requireContext(), R.string.avatar_updated, Toast.LENGTH_SHORT).show()
                findNavController().popBackStack()
            }
        }
    }

    private fun startCropMode(uri: Uri) {
        binding.rvAvatars.isVisible = false
        binding.layoutCrop.isVisible = true
        binding.cropImageView.setImageUriAsync(uri)
        backCallback.isEnabled = true
    }

    private fun exitCropMode() {
        binding.layoutCrop.isVisible = false
        binding.rvAvatars.isVisible = true
        backCallback.isEnabled = false
    }

    private fun processCroppedBitmap(bitmap: android.graphics.Bitmap) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bytes = AvatarUtils.prepareAvatarFromBitmap(bitmap)
                if (bytes != null) {
                    // 1. Сохраняем в библиотеку и получаем сущность с путем
                    val entity = repository.avatarLibraryManager.addCustomAvatar(bytes)
                    
                    // 2. Устанавливаем как текущий аватар, используя полученный путь
                    repository.updateAvatar(entity.path ?: "")
                    repository.avatarLibraryManager.updateLastUsed(entity.sha1)
                    
                    Toast.makeText(requireContext(), R.string.avatar_updated, Toast.LENGTH_SHORT).show()
                    findNavController().popBackStack()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error processing image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
