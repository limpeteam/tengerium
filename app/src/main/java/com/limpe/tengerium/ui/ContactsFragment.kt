package com.limpe.tengerium.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.databinding.FragmentContactsBinding
import com.limpe.tengerium.domain.model.Contact
import com.limpe.tengerium.domain.model.Group
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

class ContactsFragment : Fragment() {

    private var _binding: FragmentContactsBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var repository: com.limpe.tengerium.data.MSNPRepository
    private lateinit var securePrefs: com.limpe.tengerium.data.security.SecurePrefs
    private lateinit var adapter: ContactAdapter
    private val mainViewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory((requireActivity().application as TengeriumApp).repository)
    }

    private var isScrollRestored = false
    private val collapsedGroups = mutableSetOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        repository = (requireActivity().application as TengeriumApp).repository
        securePrefs = com.limpe.tengerium.data.security.SecurePrefs(requireContext())
        
        collapsedGroups.clear()
        collapsedGroups.addAll(securePrefs.getCollapsedGroups())

        setupRecyclerView()
        setupFab()
        observeData()
        observeNewContacts()
    }

    private fun setupRecyclerView() {
        adapter = ContactAdapter(
            onClick = { contact ->
                val mainFragment = findMainFragment()
                if (mainFragment != null && mainFragment.isTablet) {
                    mainFragment.openChatOnTablet(contact.account)
                } else {
                    val bundle = bundleOf("account" to contact.account)
                    findNavController().navigate(R.id.action_MainFragment_to_ChatFragment, bundle)
                }
            },
            onLongClick = { view, contact ->
                showContactContextMenu(view, contact)
            },
            onRequestsClick = {
                val mainFragment = findMainFragment()
                if (mainFragment != null && mainFragment.isTablet) {
                    mainFragment.showDetail(RequestsFragment())
                } else {
                    findNavController().navigate(R.id.action_MainFragment_to_RequestsFragment)
                }
            },
            onGroupClick = { group ->
                if (collapsedGroups.contains(group.guid)) {
                    collapsedGroups.remove(group.guid)
                } else {
                    collapsedGroups.add(group.guid)
                }
                securePrefs.setCollapsedGroups(collapsedGroups)
                refreshList()
            },
            onGroupLongClick = { view, group ->
                showGroupContextMenu(view, group)
            }
        )
        binding.rvContacts.layoutManager = LinearLayoutManager(context)
        binding.rvContacts.adapter = adapter

        binding.rvContacts.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                val firstVisibleItemView = layoutManager?.getChildAt(0)
                if (firstVisibleItemView != null) {
                    mainViewModel.contactsScrollPosition = layoutManager.findFirstVisibleItemPosition()
                    mainViewModel.contactsScrollOffset = firstVisibleItemView.top
                }
            }
        })
    }

    private fun showContactContextMenu(view: View, contact: Contact) {
        val popup = PopupMenu(requireContext(), view)
        popup.menu.add(getString(R.string.block))
        popup.menu.add(getString(R.string.contacts_settings))
        popup.menu.add(getString(R.string.delete))
        
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.block) -> repository.blockContact(contact.account)
                getString(R.string.contacts_settings) -> showChangeGroupDialog(contact)
                getString(R.string.delete) -> repository.removeContact(contact.account)
            }
            true
        }
        popup.show()
    }

    private fun showGroupContextMenu(view: View, group: Group) {
        if (group.guid == "other") return
        
        val popup = PopupMenu(requireContext(), view)
        popup.menu.add(getString(R.string.delete))
        
        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                getString(R.string.delete) -> {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.delete)
                        .setMessage(getString(R.string.clear_history_message)) // Reuse or add new string
                        .setPositiveButton(R.string.delete) { _, _ ->
                            repository.deleteGroup(group.guid)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            true
        }
        popup.show()
    }

    private fun showChangeGroupDialog(contact: Contact) {
        val groups = repository.groups.value
        if (groups.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_groups_error, Toast.LENGTH_SHORT).show()
            return
        }

        val groupNames = groups.map { it.name }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.move_to_group_title, contact.nickname.ifEmpty { contact.account }))
            .setItems(groupNames) { _, which ->
                val targetGroup = groups[which]
                val contactGuid = contact.guid
                
                if (contactGuid != null) {
                    repository.addContactToGroup(contact.account, contactGuid, targetGroup.guid)
                    val currentGroupGuids = groups.filter { it.contactAccounts.contains(contact.account.lowercase(Locale.ROOT).trim()) }.map { it.guid }
                    currentGroupGuids.forEach { oldGuid ->
                        if (oldGuid != targetGroup.guid) {
                            repository.removeContactFromGroup(contact.account, contactGuid, oldGuid)
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), R.string.contact_guid_error, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    repository.contacts,
                    repository.groups,
                    repository.pendingRequests,
                    repository.loginState
                ) { contacts, groups, requests, loginState ->
                    buildItemList(contacts, groups, requests, loginState)
                }.collect { (items, requestsCount, isConnected) ->
                    val binding = _binding ?: return@collect
                    val hadNoRequests = adapter.currentList.none { it is ContactAdapter.ContactItem.Header }
                    adapter.submitList(items) {
                        val innerBinding = _binding ?: return@submitList
                        if (!isScrollRestored) {
                            if (requestsCount > 0 && hadNoRequests && mainViewModel.contactsScrollPosition == 0) {
                                innerBinding.rvContacts.scrollToPosition(0)
                            } else if (mainViewModel.contactsScrollPosition != 0 || mainViewModel.contactsScrollOffset != 0) {
                                (innerBinding.rvContacts.layoutManager as? LinearLayoutManager)
                                    ?.scrollToPositionWithOffset(mainViewModel.contactsScrollPosition, mainViewModel.contactsScrollOffset)
                            }
                            isScrollRestored = true
                        }
                        innerBinding.rvContacts.alpha = 1f
                    }
                    
                    val hasRealContacts = items.any { it is ContactAdapter.ContactItem.User || it is ContactAdapter.ContactItem.GroupHeader }
                    val hasRequests = requestsCount > 0
                    binding.layoutEmpty.visibility = if (isConnected && !hasRealContacts && !hasRequests) View.VISIBLE else View.GONE
                    binding.layoutNotConnected.visibility = if (!isConnected) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun buildItemList(
        contacts: List<Contact>,
        groups: List<Group>,
        requests: List<Contact>,
        loginState: MSNPLoginState
    ): Triple<List<ContactAdapter.ContactItem>, Int, Boolean> {
        val items = mutableListOf<ContactAdapter.ContactItem>()
        val isConnected = loginState is MSNPLoginState.Success
        
        val myAcc = repository.getSavedAccount()?.lowercase(Locale.ROOT)?.trim()
        
        if (requests.isNotEmpty()) {
            val filteredRequests = requests.filter { it.account.lowercase(Locale.ROOT).trim() != myAcc }
            if (filteredRequests.isNotEmpty()) {
                items.add(ContactAdapter.ContactItem.Header(filteredRequests.size))
            }
        }
        
        val filteredContacts = contacts.filter { it.account.lowercase(Locale.ROOT).trim() != myAcc }

        if (groups.isNotEmpty()) {
            groups.forEach { group ->
                val groupContacts = filteredContacts.filter { contact -> 
                    group.contactAccounts.contains(contact.account.lowercase(Locale.ROOT).trim())
                }
                
                val onlineInGroup = groupContacts.count { it.status != "FLN" }
                val isExpanded = !collapsedGroups.contains(group.guid)
                
                items.add(ContactAdapter.ContactItem.GroupHeader(group, onlineInGroup, groupContacts.size, isExpanded))
                
                if (isExpanded) {
                    val sortedGroupContacts = if (securePrefs.showOnlineFirst) {
                        groupContacts.sortedWith(compareByDescending<Contact> { 
                            it.status != "FLN" 
                        }.thenBy { it.nickname.lowercase() })
                    } else {
                        groupContacts.sortedBy { it.nickname.lowercase() }
                    }
                    items.addAll(sortedGroupContacts.map { ContactAdapter.ContactItem.User(it) })
                }
            }
            
            val contactsInGroups = groups.flatMap { it.contactAccounts }.toSet()
            val otherContacts = filteredContacts.filter { !contactsInGroups.contains(it.account.lowercase(Locale.ROOT).trim()) }
            if (otherContacts.isNotEmpty()) {
                val otherGroup = Group("other", getString(R.string.other_contacts), otherContacts.map { it.account })
                val isExpanded = !collapsedGroups.contains("other")
                val onlineInOther = otherContacts.count { it.status != "FLN" }
                
                items.add(ContactAdapter.ContactItem.GroupHeader(otherGroup, onlineInOther, otherContacts.size, isExpanded))
                if (isExpanded) {
                    val sortedOther = if (securePrefs.showOnlineFirst) {
                        otherContacts.sortedWith(compareByDescending<Contact> { it.status != "FLN" }.thenBy { it.nickname.lowercase() })
                    } else {
                        otherContacts.sortedBy { it.nickname.lowercase() }
                    }
                    items.addAll(sortedOther.map { ContactAdapter.ContactItem.User(it) })
                }
            }
        } else {
            val sortedContacts = if (securePrefs.showOnlineFirst) {
                filteredContacts.sortedWith(compareByDescending<Contact> { 
                    it.status != "FLN" 
                }.thenBy { it.nickname.lowercase() })
            } else {
                filteredContacts.sortedBy { it.nickname.lowercase() }
            }
            items.addAll(sortedContacts.map { ContactAdapter.ContactItem.User(it) })
        }
        
        return Triple(items, requests.size, isConnected)
    }

    private fun refreshList() {
        viewLifecycleOwner.lifecycleScope.launch {
            val contacts = repository.contacts.value
            val groups = repository.groups.value
            val requests = repository.pendingRequests.value
            val loginState = repository.loginState.value
            
            val (items, _, _) = buildItemList(contacts, groups, requests, loginState)
            adapter.submitList(items)
        }
    }

    private fun setupFab() {
        binding.fabAddContact.setOnClickListener { view ->
            // Поворачиваем плюсик в крестик (45 градусов)
            view.animate().rotation(45f).setDuration(200).start()
            
            val popup = PopupMenu(requireContext(), view)
            popup.menuInflater.inflate(R.menu.menu_add_contact, popup.menu)
            
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_add_contact -> {
                        val mainFragment = findMainFragment()
                        if (mainFragment != null && mainFragment.isTablet) {
                            mainFragment.showDetail(AddContactFragment())
                        } else {
                            findNavController().navigate(R.id.action_MainFragment_to_AddContactFragment)
                        }
                        true
                    }
                    R.id.action_create_group -> {
                        showCreateGroupDialog()
                        true
                    }
                    else -> false
                }
            }
            
            popup.setOnDismissListener {
                // Возвращаем плюсик обратно при закрытии меню
                view.animate().rotation(0f).setDuration(200).start()
            }
            
            popup.show()
        }
    }

    private fun showCreateGroupDialog() {
        val editText = EditText(requireContext()).apply {
            hint = getString(R.string.nickname_hint)
            setSingleLine()
        }
        
        val container = FrameLayout(requireContext()).apply {
            val params = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = (24 * resources.displayMetrics.density).toInt()
                marginStart = (24 * resources.displayMetrics.density).toInt()
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
            addView(editText, params)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.create_group)
            .setView(container)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) {
                    repository.createGroup(name)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observeNewContacts() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.contactAddedFlow.collect { account ->
                    delay(300)
                    val binding = _binding ?: return@collect
                    val position = adapter.currentList.indexOfFirst { it is ContactAdapter.ContactItem.User && it.contact.account == account }
                    if (position != -1) {
                        binding.rvContacts.smoothScrollToPosition(position)
                        adapter.setHighlightedAccount(account)
                        
                        launch {
                            delay(3000)
                            if (isAdded) {
                                adapter.setHighlightedAccount(null)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun findMainFragment(): MainFragment? {
        return generateSequence(parentFragment) { it.parentFragment }
            .filterIsInstance<MainFragment>()
            .firstOrNull()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val layoutManager = _binding?.rvContacts?.layoutManager as? LinearLayoutManager
        val firstVisibleItemView = layoutManager?.getChildAt(0)
        if (firstVisibleItemView != null) {
            mainViewModel.contactsScrollPosition = layoutManager.findFirstVisibleItemPosition()
            mainViewModel.contactsScrollOffset = firstVisibleItemView.top
        }
        _binding = null
    }
}
