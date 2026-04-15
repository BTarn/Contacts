package org.fossify.contacts.activities

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import androidx.viewpager.widget.ViewPager
import me.grantland.widget.AutofitHelper
import org.fossify.commons.databases.ContactsDatabase
import org.fossify.commons.databinding.BottomTablayoutItemBinding
import org.fossify.commons.dialogs.ChangeViewTypeDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.FAQItem
import org.fossify.commons.models.RadioItem
import org.fossify.commons.models.Release
import org.fossify.commons.models.contacts.Contact
import org.fossify.contacts.BuildConfig
import org.fossify.contacts.R
import org.fossify.contacts.adapters.ViewPagerAdapter
import org.fossify.contacts.databinding.ActivityMainBinding
import org.fossify.contacts.dialogs.ChangeSortingDialog
import org.fossify.contacts.dialogs.FilterContactSourcesDialog
import org.fossify.contacts.extensions.config
import org.fossify.contacts.extensions.handleGenericContactClick
import org.fossify.contacts.extensions.tryImportContactsFromFile
import org.fossify.contacts.fragments.MyViewPagerFragment
import org.fossify.contacts.helpers.ALL_TABS_MASK
import org.fossify.contacts.helpers.tabsList
import org.fossify.contacts.interfaces.RefreshContactsListener
import java.util.Arrays

class MainActivity : SimpleActivity(), RefreshContactsListener {
    private var werePermissionsHandled = false
    private var isFirstResume = true
    private var isGettingContacts = false

    private var storedShowContactThumbnails = false
    private var storedShowPhoneNumbers = false
    private var storedStartNameWithSurname = false
    private var storedFontSize = 0
    private var storedShowTabs = 0

    override var isSearchBarEnabled = true

    private val binding by viewBinding(ActivityMainBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        appLaunched(BuildConfig.APPLICATION_ID)
        setupOptionsMenu()
        refreshMenuItems()
        setupEdgeToEdge(
            padBottomImeAndSystem = listOf(binding.mainTabsHolder),
        )
        storeStateVariables()
        setupTabs()
        checkContactPermissions()
        checkWhatsNewDialog()
    }

    private fun checkContactPermissions() {
        handlePermission(PERMISSION_READ_CONTACTS) {
            werePermissionsHandled = true
            if (it) {
                handlePermission(PERMISSION_WRITE_CONTACTS) {
                    handlePermission(PERMISSION_GET_ACCOUNTS) {
                        initFragments()
                    }
                }
            } else {
                initFragments()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (storedShowPhoneNumbers != config.showPhoneNumbers) {
            System.exit(0)
            return
        }

        if (storedShowTabs != config.showTabs) {
            config.lastUsedViewPagerPage = 0
            finish()
            startActivity(intent)
            return
        }

        val configShowContactThumbnails = config.showContactThumbnails
        if (storedShowContactThumbnails != configShowContactThumbnails) {
            getAllFragments().forEach {
                it?.showContactThumbnailsChanged(configShowContactThumbnails)
            }
        }

        val properPrimaryColor = getProperPrimaryColor()
        binding.mainTabsHolder.background = ColorDrawable(getProperBackgroundColor())
        binding.mainTabsHolder.setSelectedTabIndicatorColor(properPrimaryColor)
        getAllFragments().forEach {
            it?.setupColors(getProperTextColor(), properPrimaryColor)
        }

        updateMenuColors()
        setupTabColors()

        val configStartNameWithSurname = config.startNameWithSurname
        if (storedStartNameWithSurname != configStartNameWithSurname) {
            findViewById<MyViewPagerFragment<*>>(R.id.contacts_fragment)?.startNameWithSurnameChanged(configStartNameWithSurname)
            findViewById<MyViewPagerFragment<*>>(R.id.groups_fragment)?.startNameWithSurnameChanged(configStartNameWithSurname)
        }

        val configFontSize = config.fontSize
        if (storedFontSize != configFontSize) {
            getAllFragments().forEach {
                it?.fontSizeChanged()
            }
        }

        if (werePermissionsHandled && !isFirstResume) {
            if (binding.viewPager.adapter == null) {
                initFragments()
            } else {
                refreshContacts(ALL_TABS_MASK)
            }
        }

        val dialpadIcon =
            resources.getColoredDrawableWithColor(org.fossify.commons.R.drawable.ic_dialpad_vector, properPrimaryColor.getContrastColor())
        binding.mainDialpadButton.apply {
            setImageDrawable(dialpadIcon)
            background.applyColorFilter(properPrimaryColor)
            beVisibleIf(config.showDialpadButton)
        }

        isFirstResume = false
        checkShortcuts()
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
        config.lastUsedViewPagerPage = binding.viewPager.currentItem
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isChangingConfigurations) {
            ContactsDatabase.destroyInstance()
        }
    }

    override fun onBackPressedCompat(): Boolean {
        return if (binding.mainMenu.isSearchOpen) {
            binding.mainMenu.closeSearch()
            true
        } else {
            false
        }
    }

    private fun refreshMenuItems() {
        val currentFragment = getCurrentFragment()
        binding.mainMenu.requireToolbar().menu.apply {
            findItem(R.id.sort).isVisible = currentFragment != findViewById(R.id.groups_fragment)
            findItem(R.id.filter).isVisible = currentFragment != findViewById(R.id.groups_fragment)
            findItem(R.id.dialpad).isVisible = !config.showDialpadButton
            findItem(R.id.more_apps_from_us).isVisible = !resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)
        }
    }

    private fun setupOptionsMenu() {
        binding.mainMenu.requireToolbar().inflateMenu(R.menu.menu)
        binding.mainMenu.toggleHideOnScroll(false)
        binding.mainMenu.setupMenu()

        binding.mainMenu.onSearchClosedListener = {
            getAllFragments().forEach {
                it?.onSearchClosed()
            }
        }

        binding.mainMenu.onSearchTextChangedListener = { text ->
            getCurrentFragment()?.onSearchQueryChanged(text)
        }

        binding.mainMenu.requireToolbar().setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
//                R.id.sort -> showSortingDialog(showCustomSorting = getCurrentFragment() is FavoritesFragment)
                R.id.filter -> showFilterDialog()
                R.id.dialpad -> launchDialpad()
                R.id.more_apps_from_us -> launchMoreAppsFromUsIntent()
                R.id.settings -> launchSettings()
                R.id.about -> launchAbout()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun updateMenuColors() {
        binding.mainMenu.updateColors()
    }

    private fun storeStateVariables() {
        config.apply {
            storedShowContactThumbnails = showContactThumbnails
            storedShowPhoneNumbers = showPhoneNumbers
            storedStartNameWithSurname = startNameWithSurname
            storedShowTabs = showTabs
            storedFontSize = fontSize
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcuts() {
        val appIconColor = config.appIconColor
        if (isNougatMR1Plus() && config.lastHandledShortcutColor != appIconColor) {
            val createNewContact = getCreateNewContactShortcut(appIconColor)

            try {
                shortcutManager.dynamicShortcuts = Arrays.asList(createNewContact)
                config.lastHandledShortcutColor = appIconColor
            } catch (ignored: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getCreateNewContactShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(org.fossify.commons.R.string.create_new_contact)
        val drawable = resources.getDrawable(org.fossify.commons.R.drawable.shortcut_plus)
        (drawable as LayerDrawable).findDrawableByLayerId(org.fossify.commons.R.id.shortcut_plus_background).applyColorFilter(appIconColor)
        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, EditContactActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "create_new_contact")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
    }

    private fun getCurrentFragment(): MyViewPagerFragment<*>? {
        val showTabs = config.showTabs
        val fragments = arrayListOf<MyViewPagerFragment<*>>()
            fragments.add(findViewById(R.id.contacts_fragment))

            fragments.add(findViewById(R.id.groups_fragment))


        return fragments.getOrNull(binding.viewPager.currentItem)
    }

    private fun setupTabColors() {
        val activeView = binding.mainTabsHolder.getTabAt(binding.viewPager.currentItem)?.customView
        updateBottomTabItemColors(activeView, true, getSelectedTabDrawableIds()[binding.viewPager.currentItem])

        getInactiveTabIndexes(binding.viewPager.currentItem).forEach { index ->
            val inactiveView = binding.mainTabsHolder.getTabAt(index)?.customView
            updateBottomTabItemColors(inactiveView, false, getDeselectedTabDrawableIds()[index])
        }

        val bottomBarColor = getBottomNavigationBackgroundColor()
        binding.mainTabsHolder.setBackgroundColor(bottomBarColor)
    }

    private fun getInactiveTabIndexes(activeIndex: Int) = (0 until binding.mainTabsHolder.tabCount).filter { it != activeIndex }

    private fun getSelectedTabDrawableIds(): ArrayList<Int> {
        val showTabs = config.showTabs
        val icons = ArrayList<Int>()

            icons.add(org.fossify.commons.R.drawable.ic_person_vector)

            icons.add(org.fossify.commons.R.drawable.ic_people_vector)

        return icons
    }

    private fun getDeselectedTabDrawableIds(): ArrayList<Int> {
        val showTabs = config.showTabs
        val icons = ArrayList<Int>()

            icons.add(org.fossify.commons.R.drawable.ic_person_outline_vector)

            icons.add(org.fossify.commons.R.drawable.ic_people_outline_vector)


        return icons
    }

    private fun initFragments() {
        binding.viewPager.offscreenPageLimit = tabsList.size - 1
        binding.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                binding.mainTabsHolder.getTabAt(position)?.select()
                getAllFragments().forEach {
                    it?.finishActMode()
                }
                refreshMenuItems()
            }
        })

        binding.viewPager.onGlobalLayout {
            refreshContacts(ALL_TABS_MASK)
            refreshMenuItems()
        }

        handleExternalIntent()
        binding.mainDialpadButton.setOnClickListener {
            launchDialpad()
        }
    }

    private fun handleExternalIntent() {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }

        if (uri != null) {
            tryImportContactsFromFile(uri) { success ->
                if (success) {
                    runOnUiThread {
                        refreshContacts(ALL_TABS_MASK)
                    }
                }
            }
            intent.action = null
        }
    }

    private fun setupTabs() {
        binding.mainTabsHolder.removeAllTabs()
        tabsList.forEachIndexed { index, value ->
            binding.mainTabsHolder.newTab().setCustomView(org.fossify.commons.R.layout.bottom_tablayout_item).apply tab@{
                    customView?.let {
                        BottomTablayoutItemBinding.bind(it)
                    }?.apply {
                        tabItemIcon.setImageDrawable(getTabIcon(index))
                        tabItemLabel.text = getTabLabel(index)
                        AutofitHelper.create(tabItemLabel)
                        binding.mainTabsHolder.addTab(this@tab)
                    }
                }
        }

        binding.mainTabsHolder.onTabSelectionChanged(
            tabUnselectedAction = {
                updateBottomTabItemColors(it.customView, false, getDeselectedTabDrawableIds()[it.position])
            },
            tabSelectedAction = {
                getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
                binding.viewPager.currentItem = it.position
                updateBottomTabItemColors(it.customView, true, getSelectedTabDrawableIds()[it.position])
            }
        )

        binding.mainTabsHolder.beGoneIf(binding.mainTabsHolder.tabCount == 1)
    }

    private fun showSortingDialog(showCustomSorting: Boolean) {
        ChangeSortingDialog(this, showCustomSorting) {
            refreshContacts(TAB_CONTACTS)
        }
    }

    fun showFilterDialog() {
        FilterContactSourcesDialog(this) {
            findViewById<MyViewPagerFragment<*>>(R.id.contacts_fragment)?.forceListRedraw = true
            refreshContacts(TAB_CONTACTS)
        }
    }

    private fun launchDialpad() {
        hideKeyboard()
        Intent(Intent.ACTION_DIAL).apply {
            try {
                startActivity(this)
            } catch (e: ActivityNotFoundException) {
                toast(org.fossify.commons.R.string.no_app_found)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchAbout() {
        val licenses = LICENSE_JODA or LICENSE_GLIDE or LICENSE_GSON or LICENSE_INDICATOR_FAST_SCROLL or LICENSE_AUTOFITTEXTVIEW

        val faqItems = arrayListOf(
            FAQItem(R.string.faq_1_title, R.string.faq_1_text),
            FAQItem(org.fossify.commons.R.string.faq_9_title_commons, org.fossify.commons.R.string.faq_9_text_commons)
        )

        if (!resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)) {
            faqItems.add(FAQItem(org.fossify.commons.R.string.faq_2_title_commons, org.fossify.commons.R.string.faq_2_text_commons))
            faqItems.add(FAQItem(org.fossify.commons.R.string.faq_6_title_commons, org.fossify.commons.R.string.faq_6_text_commons))
            faqItems.add(FAQItem(org.fossify.commons.R.string.faq_7_title_commons, org.fossify.commons.R.string.faq_7_text_commons))
        }

        startAboutActivity(R.string.app_name, licenses, BuildConfig.VERSION_NAME, faqItems, true)
    }

    override fun refreshContacts(refreshTabsMask: Int) {
        if (isDestroyed || isFinishing || isGettingContacts) {
            return
        }

        isGettingContacts = true

        if (binding.viewPager.adapter == null) {
            binding.viewPager.adapter = ViewPagerAdapter(this, tabsList, config.showTabs)
            binding.viewPager.currentItem = getDefaultTab()
        }

        ContactsHelper(this).getContacts { contacts ->
            isGettingContacts = false
            if (isDestroyed || isFinishing) {
                return@getContacts
            }


                findViewById<MyViewPagerFragment<*>>(R.id.contacts_fragment)?.apply {
                    skipHashComparing = true
                    refreshContacts(contacts)
                }

                findViewById<MyViewPagerFragment<*>>(R.id.groups_fragment)?.apply {
                    if (refreshTabsMask == TAB_GROUPS) {
                        skipHashComparing = true
                    }
                    refreshContacts(contacts)
                }


            if (binding.mainMenu.isSearchOpen) {
                getCurrentFragment()?.onSearchQueryChanged(binding.mainMenu.getCurrentQuery())
            }
        }
    }

    override fun contactClicked(contact: Contact) {
        handleGenericContactClick(contact)
    }

    private fun getAllFragments() = arrayListOf<MyViewPagerFragment<*>?>(
        findViewById(R.id.contacts_fragment),
        findViewById(R.id.groups_fragment)
    )

    private fun getDefaultTab(): Int {
        return when (config.defaultTab) {
            TAB_CONTACTS -> 0
            TAB_GROUPS -> 1
            TAB_LAST_USED -> {
                // Ensure the saved index is still valid for our 2-tab setup
                if (config.lastUsedViewPagerPage in 0..1) {
                    config.lastUsedViewPagerPage
                } else {
                    0
                }
            }
            else -> 0 // Default to Contacts
        }
    }

    private fun checkWhatsNewDialog() {
        arrayListOf<Release>().apply {
            checkWhatsNew(this, BuildConfig.VERSION_CODE)
        }
    }
}
