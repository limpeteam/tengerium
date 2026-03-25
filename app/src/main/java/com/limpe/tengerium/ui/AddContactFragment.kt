package com.limpe.tengerium.ui

import android.os.Bundle
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.databinding.FragmentAddContactBinding
import com.limpe.tengerium.util.AnimationHelper

class AddContactFragment : Fragment() {

    private var _binding: FragmentAddContactBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAddContactBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.etEmail.doAfterTextChanged { text ->
            val email = text?.toString()?.trim() ?: ""
            val isValid = Patterns.EMAIL_ADDRESS.matcher(email).matches()
            
            if (isValid) {
                showResultCard(email)
            } else {
                hideResultCard()
            }
        }

        binding.etEmail.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH) {
                val email = binding.etEmail.text.toString().trim()
                if (Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    showResultCard(email)
                }
                true
            } else false
        }

        binding.btnSearch.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            if (Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                showResultCard(email)
            } else {
                binding.tilEmail.error = getString(R.string.invalid_email)
            }
        }
    }

    private fun showResultCard(email: String) {
        if (binding.itemResult.root.visibility == View.VISIBLE && binding.itemResult.tvAccount.text == email) return
        
        binding.tilEmail.error = null
        
        // Настройка карточки результата
        binding.itemResult.tvAccount.text = email
        binding.itemResult.tvMessage.text = getString(R.string.press_to_add)
        binding.itemResult.btnAccept.text = getString(R.string.add)
        binding.itemResult.btnDecline.visibility = View.GONE
        
        AvatarUtils.loadAvatar(binding.itemResult.ivAvatar, null, email)

        binding.itemResult.btnAccept.setOnClickListener {
            val repository = (requireActivity().application as TengeriumApp).repository
            repository.addContact(email)
            findNavController().popBackStack()
        }

        // Плавное появление
        if (binding.itemResult.root.visibility != View.VISIBLE) {
            AnimationHelper.fadeVisibility(binding.itemResult.root, true)
        }
    }

    private fun hideResultCard() {
        if (binding.itemResult.root.visibility == View.VISIBLE) {
            AnimationHelper.fadeVisibility(binding.itemResult.root, false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
