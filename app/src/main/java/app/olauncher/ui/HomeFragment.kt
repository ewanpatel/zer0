package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.Manifest
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Folder
import app.olauncher.data.FolderApp
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.NextEvent
import app.olauncher.helper.OlDialog
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.canReadCalendar
import app.olauncher.helper.createFolderNameDialog
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.formatNextEvent
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.millisToNextMinute
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.openCalendarEvent
import app.olauncher.helper.openCameraApp
import app.olauncher.helper.openDialerApp
import app.olauncher.helper.queryNextEvent
import app.olauncher.helper.setFolderLabel
import app.olauncher.helper.showPopupMenu
import app.olauncher.helper.showToast
import app.olauncher.listener.OnSwipeTouchListener
import app.olauncher.listener.ViewSwipeTouchListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager

    private lateinit var drawerBackCallback: OnBackPressedCallback
    private lateinit var folderBackCallback: OnBackPressedCallback

    // The open folder's home slot (0 when none) and the view listing its apps
    private var openFolderSlot = 0
    private var openFolderView: View? = null
    private var dialog: OlDialog? = null

    // Next calendar event under the date, refreshed each minute while home is showing
    private var nextEvent: NextEvent? = null
    private val nextEventHandler = Handler(Looper.getMainLooper())
    private val nextEventTick = object : Runnable {
        override fun run() {
            populateNextEvent()
            nextEventHandler.postDelayed(this, millisToNextMinute())
        }
    }
    private val calendarPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (_binding != null) populateNextEvent()
    }

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        initAppDrawer()
        initFolders()
        // Home apps never draw over the clock, date and next event
        binding.dateTimeLayout.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            binding.homeAppsLayout.clipTop = if (v.isVisible) v.bottom + 8.dpToPx() else 0
        }
        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
    }

    override fun onResume() {
        super.onResume()
        populateHomeScreen(false)
        viewModel.isOlauncherDefault()
        // Other screens may have loaded the list with hidden apps included
        viewModel.getAppList()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
        startNextEvent()
    }

    override fun onPause() {
        nextEventHandler.removeCallbacks(nextEventTick)
        super.onPause()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            // Home button for recents feature disabled
            // R.id.recents -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.nextEvent -> nextEvent?.let { openCalendarEvent(requireContext(), it) } ?: openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()

            else -> {
                try { // Launch app
                    val appLocation = view.tag.toString().toInt()
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        val slot = homeAppViews().indexOfFirst { it === view } + 1
        if (slot > 0) {
            val folder = prefs.getHomeFolder(slot)
            if (folder != null) showFolderMenu(folder, view)
            else showAppList(Constants.FLAG_SET_HOME_APP_1 + slot - 1, prefs.getAppName(slot).isNotEmpty(), true)
            return true
        }
        when (view.id) {
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> {
                showAppList(Constants.FLAG_SET_CALENDAR_APP)
                prefs.calendarAppPackage = ""
                prefs.calendarAppClassName = ""
                prefs.calendarAppUser = ""
            }

            R.id.tvScreenTime -> {
                showAppList(Constants.FLAG_SET_SCREEN_TIME_APP)
                prefs.screenTimeAppPackage = ""
                prefs.screenTimeAppClassName = ""
                prefs.screenTimeAppUser = ""
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isOlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                setHomeAlignment()
            }
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
        })
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let { binding.tvScreenTime.text = it }
        }
        viewModel.closeAppDrawer.observe(viewLifecycleOwner) {
            binding.drawerHost.close(animate = isResumed)
        }
        // Home button for recents feature disabled
        // viewModel.showRecentApps.observe(viewLifecycleOwner) {
        //     binding.recents.performClick()
        // }
    }

    private fun initAppDrawer() {
        if (appDrawer() == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.appDrawerContainer, AppDrawerFragment().apply {
                    arguments = bundleOf(
                        Constants.Key.FLAG to Constants.FLAG_LAUNCH_APP,
                        Constants.Key.EMBEDDED to true
                    )
                })
                .commitNow()
        }

        drawerBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = binding.drawerHost.close()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, drawerBackCallback)

        binding.drawerHost.apply {
            content = binding.mainLayout
            drawer = binding.appDrawerContainer
            canDrawerScrollUp = { appDrawer()?.canScrollUp() ?: false }
            onDragStart = { wasOpen ->
                closeFolder(animate = false)
                drawerBackCallback.isEnabled = true
                if (wasOpen) appDrawer()?.onDrawerDragStart()
            }
            onOpened = {
                drawerBackCallback.isEnabled = true
                appDrawer()?.onDrawerOpened()
            }
            onClosed = {
                drawerBackCallback.isEnabled = false
                appDrawer()?.onDrawerClosed()
            }
        }
    }

    private fun appDrawer() = childFragmentManager.findFragmentById(R.id.appDrawerContainer) as? AppDrawerFragment

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))
        binding.homeApp1.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp1))
        binding.homeApp2.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp2))
        binding.homeApp3.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp3))
        binding.homeApp4.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp4))
        binding.homeApp5.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp5))
        binding.homeApp6.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp6))
        binding.homeApp7.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp7))
        binding.homeApp8.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp8))
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        // Home button for recents feature disabled
        // binding.recents.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.nextEvent.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.tvScreenTime.setOnLongClickListener(this)

        // These fire only on d-pad/keyboard events; touch is consumed by ViewSwipeTouchListener
        binding.homeApp1.setOnClickListener(this)
        binding.homeApp2.setOnClickListener(this)
        binding.homeApp3.setOnClickListener(this)
        binding.homeApp4.setOnClickListener(this)
        binding.homeApp5.setOnClickListener(this)
        binding.homeApp6.setOnClickListener(this)
        binding.homeApp7.setOnClickListener(this)
        binding.homeApp8.setOnClickListener(this)
        binding.homeApp1.setOnLongClickListener(this)
        binding.homeApp2.setOnLongClickListener(this)
        binding.homeApp3.setOnLongClickListener(this)
        binding.homeApp4.setOnLongClickListener(this)
        binding.homeApp5.setOnLongClickListener(this)
        binding.homeApp6.setOnLongClickListener(this)
        binding.homeApp7.setOnLongClickListener(this)
        binding.homeApp8.setOnLongClickListener(this)
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        val verticalGravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        binding.homeAppsLayout.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        binding.homeApp1.gravity = horizontalGravity
        binding.homeApp2.gravity = horizontalGravity
        binding.homeApp3.gravity = horizontalGravity
        binding.homeApp4.gravity = horizontalGravity
        binding.homeApp5.gravity = horizontalGravity
        binding.homeApp6.gravity = horizontalGravity
        binding.homeApp7.gravity = horizontalGravity
        binding.homeApp8.gravity = horizontalGravity
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

//        var dateText = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        var dateText = dateFormat.format(Date())

        if (!prefs.showStatusBar) {
            val battery = (requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery > 0)
                dateText = getString(R.string.day_battery, dateText, battery)
        }
        binding.date.text = dateText.replace(".,", ",")
    }

    private fun startNextEvent() {
        if (!requireContext().canReadCalendar() && !prefs.calendarPermissionAsked) {
            prefs.calendarPermissionAsked = true
            calendarPermission.launch(Manifest.permission.READ_CALENDAR)
        }
        nextEventHandler.removeCallbacks(nextEventTick)
        nextEventTick.run()
    }

    private fun populateNextEvent() {
        val event = if (binding.date.isVisible) queryNextEvent(requireContext()) else null
        nextEvent = event
        binding.nextEvent.isVisible = event != null
        if (event != null) binding.nextEvent.text = formatNextEvent(requireContext(), event)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (requireContext().appUsagePermissionGranted().not()) return

        viewModel.getTodaysScreenTime()
        binding.tvScreenTime.visibility = View.VISIBLE

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val horizontalMargin = if (isLandscape) 64.dpToPx() else 10.dpToPx()
        val marginTop = if (isLandscape) {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 36.dpToPx() else 56.dpToPx()
        } else {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 45.dpToPx() else 72.dpToPx()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = marginTop
            marginStart = horizontalMargin
            marginEnd = horizontalMargin
            gravity = if (prefs.homeAlignment == Gravity.END) Gravity.START else Gravity.END
        }
        binding.tvScreenTime.layoutParams = params
        binding.tvScreenTime.setPadding(10.dpToPx())
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        if (appCountUpdated) hideHomeApps()
        populateDateTime()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            populateScreenTime()

        closeFolder(animate = false)
        homeAppViews().take(prefs.homeAppsNum).forEachIndexed { i, textView ->
            val location = i + 1
            textView.visibility = View.VISIBLE
            val folder = prefs.getHomeFolder(location)
            if (folder != null) {
                textView.setFolderLabel(folder.name)
            } else if (!setHomeAppText(
                    textView,
                    prefs.getAppName(location),
                    prefs.getAppPackage(location),
                    prefs.getAppUser(location),
                    prefs.getIsShortcut(location),
                    prefs.getShortcutId(location)
                )
            ) {
                prefs.clearHomeApp(location)
            }
        }
    }

    private fun setHomeAppText(
        textView: TextView,
        appName: String,
        packageName: String,
        userString: String,
        isShortcut: Boolean,
        shortcutId: String?,
    ): Boolean {
        // Get user handle for the app/shortcut
        val userHandle = getUserHandleFromString(requireContext(), userString)

        // If it's a shortcut, verify it still exists
        if (isShortcut) {
            val launcherApps = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

            // Query for the specific shortcut
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }

            try {
                val shortcuts = launcherApps.getShortcuts(query, userHandle)
                // Check if our shortcut still exists
                if (shortcuts?.any { it.id == shortcutId } == true) {
                    textView.text = appName.lowercase()
                    return true
                }
                textView.text = ""
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                textView.text = ""
                return false
            }
        }

        // Regular app check
        if (isPackageInstalled(requireContext(), packageName, userString)) {
            textView.text = appName.lowercase()
            return true
        }
        textView.text = ""
        return false
    }

    private fun hideHomeApps() {
        homeAppViews().forEach { it.visibility = View.GONE }
    }

    private fun homeAppViews(): List<TextView> = listOf(
        binding.homeApp1,
        binding.homeApp2,
        binding.homeApp3,
        binding.homeApp4,
        binding.homeApp5,
        binding.homeApp6,
        binding.homeApp7,
        binding.homeApp8,
    )

    private fun launchAppOrShortcut(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        if (appName.isEmpty()) return
        if (isShortcut && !shortcutId.isNullOrEmpty()) {
            launchShortcut(
                packageName = packageName,
                shortcutId = shortcutId,
                shortcutLabel = appName,
                userString = userString
            )
        } else if (packageName.isNotEmpty()) {
            launchApp(
                appName = appName,
                packageName = packageName,
                activityClassName = activityClassName,
                userString = userString
            )
        } else {
            fallback?.invoke()
        }
    }

    private fun launchShortcut(shortcutId: String, packageName: String, shortcutLabel: String, userString: String) {
        viewModel.selectedApp(
            AppModel.PinnedShortcut(
                shortcutId = shortcutId,
                appLabel = shortcutLabel,
                user = getUserHandleFromString(requireContext(), userString),
                key = null,
                appPackage = packageName,
                isNew = false,
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel.App(
                appLabel = appName,
                key = null,
                appPackage = packageName,
                activityClassName = activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun homeAppClicked(location: Int) {
        prefs.getHomeFolder(location)?.let {
            toggleFolder(location, it)
            return
        }
        launchAppOrShortcut(
            appName = prefs.getAppName(location),
            packageName = prefs.getAppPackage(location),
            activityClassName = prefs.getAppActivityClassName(location),
            shortcutId = prefs.getShortcutId(location),
            isShortcut = prefs.getIsShortcut(location),
            userString = prefs.getAppUser(location)
        )
    }

    private fun openSwipeRightApp() {
        if (!prefs.swipeRightEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeRight,
            packageName = prefs.appPackageSwipeRight,
            activityClassName = prefs.appActivityClassNameRight,
            shortcutId = prefs.shortcutIdSwipeRight,
            isShortcut = prefs.isShortcutSwipeRight,
            userString = prefs.appUserSwipeRight,
            fallback = { openDialerApp(requireContext()) }
        )
    }

    private fun openSwipeLeftApp() {
        if (!prefs.swipeLeftEnabled) return
        launchAppOrShortcut(
            appName = prefs.appNameSwipeLeft,
            packageName = prefs.appPackageSwipeLeft,
            activityClassName = prefs.appActivityClassNameSwipeLeft,
            shortcutId = prefs.shortcutIdSwipeLeft,
            isShortcut = prefs.isShortcutSwipeLeft,
            userString = prefs.appUserSwipeLeft,
            fallback = { openCameraApp(requireContext()) }
        )
    }

    private fun showAppList(
        flag: Int,
        rename: Boolean = false,
        includeHiddenApps: Boolean = false,
        folderId: String? = null,
    ) {
        viewModel.getAppList(includeHiddenApps)
        val args = bundleOf(
            Constants.Key.FLAG to flag,
            Constants.Key.RENAME to rename,
            Constants.Key.FOLDER_ID to folderId
        )
        try {
            findNavController().navigate(R.id.action_mainFragment_to_appListFragment, args)
        } catch (e: Exception) {
            findNavController().navigate(R.id.appListFragment, args)
            e.printStackTrace()
        }
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun openScreenTimeDigitalWellbeing() {
        if (prefs.screenTimeAppPackage.isNotBlank()) {
            launchApp(
                "Screen Time",
                prefs.screenTimeAppPackage,
                prefs.screenTimeAppClassName,
                prefs.screenTimeAppUser
            )
            return
        }
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Folders

    private fun initFolders() {
        folderBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = closeFolder()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, folderBackCallback)
    }

    private fun toggleFolder(location: Int, folder: Folder) {
        if (openFolderSlot == location) {
            closeFolder()
            return
        }
        val apps = folder.apps
            .filter { isPackageInstalled(requireContext(), it.packageName, it.user) }
            .sortedBy { folderAppLabel(it).lowercase() }
        if (apps.isEmpty()) {
            showAppList(Constants.FLAG_EDIT_FOLDER, includeHiddenApps = true, folderId = folder.id)
            return
        }

        TransitionManager.beginDelayedTransition(binding.homeAppsLayout, AutoTransition().setDuration(FOLDER_ANIM_MS))
        closeFolder(animate = false)
        val slotView = homeAppViews()[location - 1]
        val folderView = createFolderView(apps)
        binding.homeAppsLayout.addView(folderView, binding.homeAppsLayout.indexOfChild(slotView) + 1)
        homeAppViews().forEach { it.alpha = if (it === slotView) 1f else FOLDER_DIM_ALPHA }
        binding.homeAppsLayout.keepInView(slotView, folderView)
        openFolderSlot = location
        openFolderView = folderView
        folderBackCallback.isEnabled = true
    }

    private fun closeFolder(animate: Boolean = true) {
        val folderView = openFolderView ?: return
        val binding = _binding ?: return
        if (animate) {
            TransitionManager.beginDelayedTransition(binding.homeAppsLayout, AutoTransition().setDuration(FOLDER_ANIM_MS))
            binding.homeAppsLayout.keepInView(null, null)
        } else binding.homeAppsLayout.resetScroll()
        binding.homeAppsLayout.removeView(folderView)
        homeAppViews().forEach { it.alpha = 1f }
        openFolderSlot = 0
        openFolderView = null
        folderBackCallback.isEnabled = false
    }

    // The folder's apps in smaller, softer text, with a faint line down the end edge
    private fun createFolderView(apps: List<FolderApp>): View {
        val context = requireContext()
        val textSize = resources.getDimension(R.dimen.text_large) * 0.82f
        val padding = resources.getDimensionPixelSize(R.dimen.home_app_padding_vertical) * 2 / 3

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = prefs.homeAlignment
        }
        apps.forEach { app ->
            column.addView(TextView(context, null, 0, R.style.AppName).apply {
                text = folderAppLabel(app).lowercase()
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize)
                setPadding(0, padding, 0, padding)
                gravity = prefs.homeAlignment
                alpha = 0.7f
                setOnClickListener {
                    launchApp(folderAppLabel(app), app.packageName, app.activityClassName, app.user)
                }
            })
        }

        val line = View(context).apply {
            setBackgroundColor(context.getColorFromAttr(R.attr.primaryColor))
            alpha = 0.2f
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 2.dpToPx()
                bottomMargin = 6.dpToPx()
            }
            addView(column, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 14.dpToPx() })
            addView(line, LinearLayout.LayoutParams(1.dpToPx(), LinearLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun folderAppLabel(app: FolderApp): String = prefs.getAppRenameLabel(app.packageName).ifBlank { app.label }

    private fun showFolderMenu(folder: Folder, anchor: View) {
        anchor.showPopupMenu(configure = { menu ->
            menu.add(0, R.string.edit_apps, 0, R.string.edit_apps)
            menu.add(0, R.string.rename, 1, R.string.rename)
            menu.add(0, R.string.delete_folder, 2, R.string.delete_folder)
        }) { item ->
            when (item.itemId) {
                R.string.edit_apps ->
                    showAppList(Constants.FLAG_EDIT_FOLDER, includeHiddenApps = true, folderId = folder.id)

                R.string.rename -> {
                    dialog?.dismiss()
                    dialog = requireContext().createFolderNameDialog(
                        title = R.string.rename,
                        action = R.string.rename,
                        initial = folder.name,
                    ) { name ->
                        prefs.getFolder(folder.id)?.let { prefs.saveFolder(it.copy(name = name)) }
                        populateHomeScreen(false)
                    }.also { it.showRespectingStatusBar() }
                }

                R.string.delete_folder -> {
                    prefs.deleteFolder(folder.id)
                    populateHomeScreen(false)
                }
            }
        }
    }

    // Holding empty space dims the home screen while settings is about to open
    private fun dimHome(dim: Boolean, animate: Boolean = true) {
        val alpha = if (dim) HOLD_DIM_ALPHA else 1f
        listOf(binding.dateTimeLayout, binding.tvScreenTime, binding.homeAppsLayout, binding.setDefaultLauncher).forEach {
            it.animate().cancel()
            if (animate) it.animate().alpha(alpha).setDuration(if (dim) Constants.LONG_PRESS_DELAY_MS else 150L).start()
            else it.alpha = alpha
        }
    }

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getSwipeGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                expandNotificationDrawer(requireContext())
            }

            override fun onLongPressStart() {
                super.onLongPressStart()
                dimHome(true)
            }

            override fun onLongPressCancel() {
                super.onLongPressCancel()
                dimHome(false)
            }

            override fun onLongClick() {
                super.onLongClick()
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                    viewModel.firstOpen(false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onClick() {
                super.onClick()
                closeFolder()
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                if (!prefs.lockModeOn) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    binding.lock.performClick()
                else
                    lockPhone()
            }
        }
    }

    private fun getViewSwipeTouchListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                expandNotificationDrawer(requireContext())
            }

            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }
        }
    }

    override fun onStop() {
        // Leaving home (e.g. an app was launched): reset the drawer out of sight
        binding.drawerHost.close(animate = false)
        closeFolder(animate = false)
        dimHome(false, animate = false)
        super.onStop()
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        openFolderView = null
        openFolderSlot = 0
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val FOLDER_ANIM_MS = 180L
        private const val FOLDER_DIM_ALPHA = 0.28f
        private const val HOLD_DIM_ALPHA = 0.4f
    }
}
