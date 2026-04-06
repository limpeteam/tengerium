package com.limpe.tengerium.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.FragmentInviteParticipantsBinding
import com.limpe.tengerium.domain.model.Contact
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class InviteParticipantsFragment : Fragment() {

    private var _binding: FragmentInviteParticipantsBinding? = null
    private val binding get() = _binding!!
    
    private val args: InviteParticipantsFragmentArgs by navArgs()
    private val mainViewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory((requireActivity().application as TengeriumApp).repository)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInviteParticipantsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repository = (requireActivity().application as TengeriumApp).repository
        val securePrefs = SecurePrefs(requireContext())
        val chatAccount = args.account

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        val adapter = ContactAdapter(
            securePrefs = securePrefs,
            onClick = { contact ->
                repository.inviteContactToChat(chatAccount, contact.account)
                findNavController().popBackStack()
            },
            onLongClick = { _, _ -> },
            onRequestsClick = {},
            onGroupClick = {},
            onGroupLongClick = { _, _ -> }
        )

        binding.rvContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContacts.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.contacts.collectLatest { contacts ->
                    val filtered = contacts.filter { 
                        it.account.lowercase(Locale.ROOT) != repository.getCurrentAccount()?.lowercase(Locale.ROOT) &&
                        it.account.lowercase(Locale.ROOT) != chatAccount.lowercase(Locale.ROOT)
                    }.sortedWith(compareByDescending<Contact> { it.status != "FLN" }.thenBy { it.nickname.lowercase(Locale.ROOT) })
                    
                    val items = filtered.map { ContactAdapter.ContactItem.User(it) }
                    adapter.submitList(items)
                    binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
