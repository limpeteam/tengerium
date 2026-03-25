package com.limpe.tengerium.ui

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.google.android.material.tabs.TabLayoutMediator
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.FragmentMainWithTabsBinding
import com.limpe.tengerium.util.AnimationHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import uniffi.msnp11_sdk.Config

class MainFragment : Fragment() {

    private var _binding: FragmentMainWithTabsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels {
        MainViewModel.Factory((requireActivity().application as TengeriumApp).repository)
    }

    var isTablet: Boolean = false
        private set

    private lateinit var securePrefs: SecurePrefs
    private var pagerAdapter: MainPagerAdapter? = null
    private var tabLayoutMediator: TabLayoutMediator? = null
    private var hasServices = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = FragmentMainWithTabsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val repository = (requireActivity().application as TengeriumApp).repository
        securePrefs = SecurePrefs(requireContext())
        
        isTablet = view.findViewById<View>(R.id.leftPanel) != null
        Log.d("MainFragment", "isTablet: $isTablet")

        val initialConfig = repository.config.value
        hasServices = initialConfig != null && (initialConfig.tabs.isNotEmpty() || initialConfig.msnTodayUrl.isNotEmpty())

        pagerAdapter = MainPagerAdapter(this)
        pagerAdapter?.updateServices(hasServices)
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.isUserInputEnabled = !isTablet

        setupTabs()

        applyDynamicDesign()

        if (isTablet) {
            setupTabletLayout()
            childFragmentManager.addOnBackStackChangedListener {
                updateDetailVisibility()
            }
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewModel.lastMainTab = position
            }
        })

        val accountArg = arguments?.getString("account")
        if (accountArg != null) {
            Log.d("MainFragment", "Opening chat from arguments: $accountArg")
            if (isTablet) {
                openChatOnTablet(accountArg)
            } else {
                val bundle = bundleOf("account" to accountArg)
                findNavController().navigate(R.id.action_MainFragment_to_ChatFragment, bundle)
            }
            arguments?.remove("account")
        } else if (savedInstanceState == null) {
            if (viewModel.lastMainTab != -1) {
                binding.viewPager.post {
                    binding.viewPager.setCurrentItem(viewModel.lastMainTab, false)
                }
            } else if (securePrefs.openChatsByDefault) {
                binding.viewPager.post {
                    binding.viewPager.setCurrentItem(1, false)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.loginState.collectLatest { state ->
                    updateConnectionStatus(state is MSNPLoginState.Loading || state is MSNPLoginState.Reconnecting)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.getUnreadChatsCount().collectLatest { count ->
                    updateChatBadge(count)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.config.collectLatest { config ->
                    val newHasServices = config != null && (config.tabs.isNotEmpty() || config.msnTodayUrl.isNotEmpty())
                    Log.d("MainFragment", "Config updated: hasServices=$newHasServices")
                    if (newHasServices != hasServices) {
                        hasServices = newHasServices
                        pagerAdapter?.updateServices(hasServices)
                        
                        binding.tabLayout.post { 
                            setupTabs() 
                            val servicesTabIndex = if (hasServices) 2 else -1
                            if (servicesTabIndex != -1) {
                                binding.tabLayout.getTabAt(servicesTabIndex)?.customView?.let {
                                    AnimationHelper.animateTabIn(it)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (isTablet) {
            view.post { updateDetailVisibility() }
        }
    }

    private fun setupTabs() {
        tabLayoutMediator?.detach()
        
        tabLayoutMediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.setCustomView(R.layout.view_tab_item)
            val tabTitle = tab.customView?.findViewById<TextView>(R.id.tabTitle)
            
            val titles = if (hasServices) {
                listOf(
                    getString(R.string.tab_contacts),
                    getString(R.string.tab_chats),
                    getString(R.string.tab_services),
                    getString(R.string.tab_profile)
                )
            } else {
                listOf(
                    getString(R.string.tab_contacts),
                    getString(R.string.tab_chats),
                    getString(R.string.tab_profile)
                )
            }

            if (position < titles.size) {
                tabTitle?.text = titles[position]
            }
        }
        tabLayoutMediator?.attach()
    }

    private fun applyDynamicDesign() {
        val surfaceColor = com.google.android.material.color.MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorSurface, 0)

        binding.appBarLayout.setBackgroundColor(surfaceColor)
        binding.mainToolbar.setBackgroundColor(surfaceColor)
        binding.tabLayout.setBackgroundColor(surfaceColor)
        binding.appBarLayout.elevation = 0f
    }

    private fun setupTabletLayout() {
        val placeholderBg = view?.findViewById<android.widget.ImageView>(R.id.ivPlaceholderBackground)
        val bgPath = securePrefs.chatBackgroundPath
        if (!bgPath.isNullOrEmpty() && placeholderBg != null) {
            placeholderBg.isVisible = true
            placeholderBg.load(Uri.parse(bgPath))
        }
    }

    fun showDetail(fragment: Fragment) {
        if (!isTablet) return
        
        val containerId = R.id.chat_nav_container
        val current = childFragmentManager.findFragmentById(containerId)
        
        if (current?.javaClass == fragment.javaClass) {
            when (fragment) {
                is ChatFragment -> {
                    if (current is ChatFragment && 
                        current.arguments?.getString("account") == fragment.arguments?.getString("account")) {
                        Log.d("MainFragment", "Chat already open, skipping")
                        return
                    }
                }
                is WebViewFragment -> {
                    if (current is WebViewFragment && 
                        current.arguments?.getString("url") == fragment.arguments?.getString("url")) {
                        Log.d("MainFragment", "Service already open, skipping")
                        return
                    }
                }
            }
        }

        Log.d("MainFragment", "Replacing fragment in detail panel: ${fragment.javaClass.simpleName}")

        val isBaseDetail = fragment is ChatFragment || fragment is WebViewFragment

        if (isBaseDetail) {
            if (childFragmentManager.backStackEntryCount > 0) {
                childFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }
            childFragmentManager.commitNow {
                replace(containerId, fragment)
            }
        } else {
            childFragmentManager.commit {
                setCustomAnimations(
                    R.anim.metro_enter, 
                    R.anim.metro_exit, 
                    R.anim.metro_pop_enter, 
                    R.anim.metro_pop_exit
                )
                replace(containerId, fragment)
                addToBackStack(null)
            }
            childFragmentManager.executePendingTransactions()
        }
        
        updateDetailVisibility()
    }

    private fun updateDetailVisibility() {
        if (!isTablet || _binding == null) return
        
        val container = view?.findViewById<View>(R.id.chat_nav_container)
        val placeholder = view?.findViewById<View>(R.id.layoutPlaceholder)
        
        if (container == null || placeholder == null) return
        
        val hasDetail = childFragmentManager.findFragmentById(R.id.chat_nav_container) != null
        
        Log.d("MainFragment", "updateDetailVisibility: hasDetail=$hasDetail")
        
        placeholder.isVisible = !hasDetail
        container.isVisible = hasDetail
    }

    fun openChatOnTablet(account: String) {
        val fragment = ChatFragment().apply {
            arguments = Bundle().apply { putString("account", account) }
        }
        showDetail(fragment)
    }

    private fun updateConnectionStatus(isConnecting: Boolean) {
        val statusView = binding.mainToolbar.findViewById<TextView>(R.id.tvToolbarStatus) ?: return
        if (isConnecting) {
            statusView.setText(R.string.connecting_to_msn)
            if (!statusView.isVisible) {
                statusView.isVisible = true
                statusView.alpha = 0f
                statusView.animate().alpha(1f).setDuration(300).start()
            }
        } else {
            if (statusView.isVisible) {
                statusView.animate().alpha(0f).setDuration(300).withEndAction { statusView.isVisible = false }.start()
            }
        }
    }

    private fun updateChatBadge(count: Int) {
        val chatTab = binding.tabLayout.getTabAt(1) ?: return
        val badge = chatTab.customView?.findViewById<View>(R.id.tabBadge) ?: return
        val badgeText = chatTab.customView?.findViewById<TextView>(R.id.tabBadgeText) ?: return

        badge.isVisible = count > 0
        if (count > 0) badgeText.text = count.toString()
    }

    private class MainPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        private var showServices = false

        fun updateServices(show: Boolean) {
            if (showServices != show) {
                showServices = show
                notifyDataSetChanged()
            }
        }

        override fun getItemCount(): Int = if (showServices) 4 else 3
        
        override fun createFragment(position: Int): Fragment {
            return if (showServices) {
                when (position) {
                    0 -> ContactsFragment()
                    1 -> ChatsFragment()
                    2 -> ServicesFragment()
                    3 -> ProfileFragment()
                    else -> Fragment()
                }
            } else {
                when (position) {
                    0 -> ContactsFragment()
                    1 -> ChatsFragment()
                    2 -> ProfileFragment()
                    else -> Fragment()
                }
            }
        }
        
        override fun getItemId(position: Int): Long {
            return if (showServices) {
                when(position) {
                    0 -> 1000L
                    1 -> 1001L
                    2 -> 1002L
                    3 -> 1003L
                    else -> super.getItemId(position)
                }
            } else {
                when(position) {
                    0 -> 1000L
                    1 -> 1001L
                    2 -> 1003L
                    else -> super.getItemId(position)
                }
            }
        }
        
        override fun containsItem(itemId: Long): Boolean {
            val validIds = if (showServices) {
                setOf(1000L, 1001L, 1002L, 1003L)
            } else {
                setOf(1000L, 1001L, 1003L)
            }
            return validIds.contains(itemId)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tabLayoutMediator?.detach()
        tabLayoutMediator = null
        _binding = null
    }
}
