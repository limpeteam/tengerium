package com.limpe.tengerium.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.slidingpanelayout.widget.SlidingPaneLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.limpe.tengerium.R
import com.limpe.tengerium.TengeriumApp
import com.limpe.tengerium.data.MSNPLoginState
import com.limpe.tengerium.data.MSNPRepository
import com.limpe.tengerium.data.security.SecurePrefs
import com.limpe.tengerium.databinding.FragmentMainWithTabsBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Основной фрагмент приложения.
 * Он рулит вкладками (контакты, чаты, профиль) и показывает детали (типа чатов)
 * через SlidingPaneLayout, чтобы на планшетах всё выглядело красиво.
 */
class MainFragment : Fragment() {

    // Тут держим наш биндинг
    private var _binding: FragmentMainWithTabsBinding? = null
    // Удобная обертка, чтобы не писать лишние вопросики
    val binding get() = _binding!!

    // Флаг, чтобы понимать, планшет у нас или обычный мобильник
    var isTablet: Boolean = false
        private set

    // Хранилище для зашифрованных настроек
    private lateinit var securePrefs: SecurePrefs
    // Адаптер для наших вкладок в ViewPager
    private var pagerAdapter: MainPagerAdapter? = null
    // Штука, которая связывает TabLayout и ViewPager
    private var tabLayoutMediator: TabLayoutMediator? = null

    // Надуваем макет фрагмента
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMainWithTabsBinding.inflate(inflater, container, false)
        return binding.root
    }

    // Когда вью создана, начинаем всё настраивать
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val repository = (requireActivity().application as TengeriumApp).repository
        securePrefs = SecurePrefs(requireContext())
        
        // Убрали sliderFadeColor, так как он deprecated
        binding.slidingPaneLayout.lockMode = SlidingPaneLayout.LOCK_MODE_LOCKED_CLOSED
        
        val onBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                closeDetail()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, onBackPressedCallback)

        binding.slidingPaneLayout.addPanelSlideListener(object : SlidingPaneLayout.PanelSlideListener {
            override fun onPanelSlide(panel: View, slideOffset: Float) {}
            override fun onPanelOpened(panel: View) {
                onBackPressedCallback.isEnabled = true
                updateSlidingPaneLockMode()
            }
            override fun onPanelClosed(panel: View) {
                onBackPressedCallback.isEnabled = false
                // Когда закрыто - блокируем, чтобы не было случайных свайпов по пустоте
                binding.slidingPaneLayout.lockMode = SlidingPaneLayout.LOCK_MODE_LOCKED_CLOSED
                if (binding.slidingPaneLayout.isSlideable) {
                    clearTemporaryDetails()
                }
            }
        })

        childFragmentManager.addOnBackStackChangedListener {
            updateSlidingPaneLockMode()
        }

        isTablet = !binding.slidingPaneLayout.isSlideable

        pagerAdapter = MainPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        setupTabs()
        applyDynamicDesign()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.loginState.collectLatest { state ->
                    updateConnectionStatus(state is MSNPLoginState.Loading || state is MSNPLoginState.Reconnecting)
                }
            }
        }

        observeUnreadCount(repository)
    }

    private fun observeUnreadCount(repository: MSNPRepository) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.getUnreadChatsCount().collectLatest { count ->
                    updateTabBadge(1, count) // 1 - индекс вкладки чатов
                }
            }
        }
    }

    private fun updateTabBadge(tabIndex: Int, count: Int) {
        val tab = binding.tabLayout.getTabAt(tabIndex) ?: return
        val customView = tab.customView ?: return
        val badgeFrame = customView.findViewById<View>(R.id.tabBadge) ?: return
        val badgeText = customView.findViewById<TextView>(R.id.tabBadgeText) ?: return

        if (count > 0) {
            badgeFrame.visibility = View.VISIBLE
            badgeText.text = if (count > 9) "9+" else count.toString()
        } else {
            badgeFrame.visibility = View.GONE
        }
    }

    // Решаем, когда можно свайпать панель, а когда она должна стоять мертво
    private fun updateSlidingPaneLockMode() {
        if (!binding.slidingPaneLayout.isSlideable) return
        
        val hasDetail = childFragmentManager.findFragmentById(R.id.detail_container) != null
        val hasBackStack = childFragmentManager.backStackEntryCount > 0

        binding.slidingPaneLayout.lockMode = when {
            !hasDetail -> SlidingPaneLayout.LOCK_MODE_LOCKED_CLOSED
            hasBackStack -> SlidingPaneLayout.LOCK_MODE_LOCKED_OPEN // Блокируем в открытом виде, если есть внутренний стек
            else -> SlidingPaneLayout.LOCK_MODE_UNLOCKED // РАЗБЛОКИРУЕМ для бесшовного свайпа SlidingPaneLayout
        }
    }

    // Подметаем за собой детали на телефонах, чтобы ничего не наслаивалось
    private fun clearTemporaryDetails() {
        // На мобильных устройствах при закрытии панели всегда очищаем детали,
        // чтобы предотвратить "наслоение" фрагментов при следующем открытии.
        val current = childFragmentManager.findFragmentById(R.id.detail_container)
        if (current != null) {
            clearAllDetails()
        }
    }

    // Показываем какой-нибудь детальный экран (например, чат с кем-то)
    fun showDetail(fragment: Fragment, addToBackStack: Boolean = false) {
        val currentFragment = childFragmentManager.findFragmentById(R.id.detail_container)
        // Если это первый фрагмент в контейнере деталей, не добавляем его в BackStack,
        // чтобы следующее действие "назад" или свайп сразу закрывали панель, а не переходили к пустому экрану.
        val shouldAddToBackStack = addToBackStack && currentFragment != null

        childFragmentManager.commit {
            setCustomAnimations(
                R.anim.metro_enter,
                R.anim.metro_exit,
                R.anim.metro_pop_enter,
                R.anim.metro_pop_exit
            )
            setReorderingAllowed(true)
            if (currentFragment != null) {
                add(R.id.detail_container, fragment)
            } else {
                replace(R.id.detail_container, fragment)
            }
            
            if (shouldAddToBackStack) {
                addToBackStack(null)
            }
        }
        binding.slidingPaneLayout.openPane()
        updateSlidingPaneLockMode()
    }

    // Пытаемся закрыть деталь или уйти назад по стеку
    fun closeDetail() {
        if (childFragmentManager.backStackEntryCount > 0) {
            childFragmentManager.popBackStackImmediate()
            updateSlidingPaneLockMode()
            
            // Если после поп-бэкстека контейнер оказался пустым, значит мы вернулись в "пустое" состояние.
            // На мобильных устройствах в этом случае нужно закрыть панель.
            if (childFragmentManager.findFragmentById(R.id.detail_container) == null && binding.slidingPaneLayout.isSlideable) {
                binding.slidingPaneLayout.closePane()
            }
            return
        }
        
        if (binding.slidingPaneLayout.isOpen) {
            binding.slidingPaneLayout.closePane()
        }
    }

    // Вычищаем всё из контейнера деталей под ноль
    fun clearAllDetails() {
        childFragmentManager.commitNow {
            while(childFragmentManager.backStackEntryCount > 0) {
                childFragmentManager.popBackStackImmediate()
            }
            val fragment = childFragmentManager.findFragmentById(R.id.detail_container)
            if (fragment != null) remove(fragment)
        }
        updateSlidingPaneLockMode()
    }

    // Открываем чат специально в режиме планшета (справа)
    fun openChatOnTablet(account: String) {
        val fragment = ChatFragment().apply {
            arguments = bundleOf("account" to account)
        }
        childFragmentManager.commit {
            setCustomAnimations(
                R.anim.metro_enter,
                R.anim.metro_exit,
                R.anim.metro_pop_enter,
                R.anim.metro_pop_exit
            )
            replace(R.id.detail_container, fragment)
        }
        binding.slidingPaneLayout.openPane()
        updateSlidingPaneLockMode()
    }

    // Обновляем статус подключения в тулбаре
    private fun updateConnectionStatus(isConnecting: Boolean) {
        val statusView = binding.toolbarLayout?.root?.findViewById<TextView>(R.id.tvToolbarStatus) ?: return
        statusView.isVisible = isConnecting
    }

    // Настраиваем вкладки и их заголовки
    private fun setupTabs() {
        tabLayoutMediator?.detach()
        tabLayoutMediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.setCustomView(R.layout.view_tab_item)
            val titles = listOf(getString(R.string.tab_contacts), getString(R.string.tab_chats), getString(R.string.tab_profile))
            tab.customView?.findViewById<TextView>(R.id.tabTitle)?.text = titles.getOrNull(position)
        }
        tabLayoutMediator?.attach()
    }

    // Наводим марафет: подкрашиваем тулбары и табы в цвет фона
    private fun applyDynamicDesign() {
        // Явно указываем material R, чтобы избежать путаницы с локальными ресурсами
        val surfaceColor = com.google.android.material.color.MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorSurface, Color.WHITE)
        binding.appBarLayout.setBackgroundColor(surfaceColor)
        binding.mainToolbar.setBackgroundColor(surfaceColor)
        binding.tabLayout.setBackgroundColor(surfaceColor)
    }

    // Маленький помощник для переключения фрагментов во вкладках
    private class MainPagerAdapter(fragment: Fragment) : androidx.viewpager2.adapter.FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ContactsFragment()
                1 -> ChatsFragment()
                else -> ProfileFragment()
            }
        }
    }

    // Прибираемся, когда вью фрагмента уничтожается
    override fun onDestroyView() {
        super.onDestroyView()
        tabLayoutMediator?.detach()
        _binding = null
    }
}
