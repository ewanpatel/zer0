package app.olauncher.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Recycler
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Folder
import app.olauncher.data.FolderApp
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentAppDrawerBinding
import app.olauncher.helper.OlDialog
import app.olauncher.helper.SearchCommand
import app.olauncher.helper.VERB_ALLOW_CONTACTS
import app.olauncher.helper.createFolderNameDialog
import app.olauncher.helper.deletePinnedShortcut
import app.olauncher.helper.dpToPx
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemAnimationsDisabled
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.openAppInfo
import app.olauncher.helper.openSearch
import app.olauncher.helper.openUrl
import app.olauncher.helper.parseSearchCommands
import app.olauncher.helper.setFolderLabel
import app.olauncher.helper.showPopupMenu
import app.olauncher.helper.showKeyboard
import app.olauncher.helper.showToast
import app.olauncher.helper.uninstall

class AppDrawerFragment : BaseFragment() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: AppDrawerAdapter
    private lateinit var linearLayoutManager: LinearLayoutManager
    private var searchTextView: TextView? = null
    private var cachedIsCjkKeyboard: Boolean? = null

    private var flag = Constants.FLAG_LAUNCH_APP
    private var canRename = false

    // Embedded in the home screen as a slide-up drawer, rather than shown as its own screen
    private var embedded = false
    private var isDrawerOpen = false

    // Editing a folder's apps: its id and the apps currently in it
    private var folderId: String? = null
    private val folderMembers = mutableSetOf<String>()
    private var dialog: OlDialog? = null

    // Search commands for the current query (launcher drawer only)
    private var commands: List<SearchCommand> = emptyList()
    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        _binding?.let { binding -> updateCommands(binding.search.query.toString()) }
    }
    private var currentAppList: List<AppModel>? = null
    private var currentPrivateSpaceApps: List<AppModel>? = null
    private var currentPrivateSpaceLocked: Boolean = true
    private var currentPrivateSpaceAvailable: Boolean = false

    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentAppDrawerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAppDrawerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        arguments?.let {
            flag = it.getInt(Constants.Key.FLAG, Constants.FLAG_LAUNCH_APP)
            canRename = it.getBoolean(Constants.Key.RENAME, false)
            embedded = it.getBoolean(Constants.Key.EMBEDDED, false)
            folderId = it.getString(Constants.Key.FOLDER_ID)
        }

        initViews()
        initSearch()
        initAdapter()
        initObservers()
        initClickListeners()
    }

    private fun initViews() {
        if (flag == Constants.FLAG_HIDDEN_APPS)
            binding.search.queryHint = getString(R.string.hidden_apps)
        else if (flag in Constants.FLAG_SET_HOME_APP_1..Constants.FLAG_SET_CALENDAR_APP)
            binding.search.queryHint = "Please select an app"
        else if (flag == Constants.FLAG_EDIT_FOLDER) {
            val folder = folderId?.let { prefs.getFolder(it) }
            folder?.apps?.mapTo(folderMembers) { it.key }
            binding.search.queryHint = getString(R.string.folder_edit_hint, folder?.name?.lowercase().orEmpty())
        }
        if (flag in Constants.FLAG_SET_HOME_APP_1..Constants.FLAG_SET_HOME_APP_8) {
            binding.newFolder.setFolderLabel(getString(R.string.new_folder))
            binding.newFolder.visibility = View.VISIBLE
        }
        try {
            searchTextView = binding.search.findViewById(R.id.search_src_text)
            searchTextView?.gravity = prefs.appLabelAlignment
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initSearch() {
        binding.search.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (commands.isNotEmpty())
                    runCommand(commands.first())
                else if (query?.startsWith("!") == true)
                    requireContext().openUrl(Constants.URL_DUCK_SEARCH + query.replace(" ", "%20"))
                else if (adapter.itemCount == 0)
                    requireContext().openSearch(query?.trim())
                else
                    adapter.launchFirstInList()
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                try {
                    updateCommands(newText)
                    adapter.allowAutoLaunch = !isSearchComposing() && commands.isEmpty()
                    adapter.filter.filter(newText)
                    binding.appRename.visibility =
                        if (canRename && newText.isNotBlank()) View.VISIBLE else View.GONE
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return false
            }
        })
    }

    private fun isSearchComposing(): Boolean {
        val text = searchTextView?.text
        if (text !is Spannable) return false
        val start = BaseInputConnection.getComposingSpanStart(text)
        val end = BaseInputConnection.getComposingSpanEnd(text)
        if (start !in 0 until end) return false
        return isCjkKeyboard()
    }

    private fun isCjkKeyboard(): Boolean {
        cachedIsCjkKeyboard?.let { return it }
        val result = try {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val subtype = imm.currentInputMethodSubtype
            val language = when {
                subtype == null -> ""
                subtype.languageTag.isNotEmpty() -> subtype.languageTag // e.g. "zh-CN", "ja-JP", "en-US"
                else -> subtype.locale // deprecated fallback, e.g. "zh_CN"
            }
            language.startsWith("zh") || language.startsWith("ja") || language.startsWith("ko")
        } catch (e: Exception) {
            false
        }
        cachedIsCjkKeyboard = result
        return result
    }

    private fun initAdapter() {
        adapter = AppDrawerAdapter(
            flag,
            prefs.appLabelAlignment,
            appClickListener = { appModel ->
                if (flag == Constants.FLAG_EDIT_FOLDER) {
                    toggleFolderApp(appModel)
                    return@AppDrawerAdapter
                }
                viewModel.selectedApp(appModel, flag)
                if (embedded) return@AppDrawerAdapter
                if (flag == Constants.FLAG_LAUNCH_APP || flag == Constants.FLAG_HIDDEN_APPS)
                    findNavController().popBackStack(R.id.mainFragment, false)
                else
                    findNavController().popBackStack()
            },
            appInfoListener = {
                openAppInfo(
                    requireContext(),
                    it.user,
                    it.appPackage
                )
                if (!embedded) findNavController().popBackStack(R.id.mainFragment, false)
            },
            appDeleteListener = { appModel ->
                when (appModel) {
                    is AppModel.PrivateSpaceHeader -> {}
                    is AppModel.PinnedShortcut ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                            requireContext().deletePinnedShortcut(
                                packageName = appModel.appPackage,
                                shortcutIdToDelete = appModel.shortcutId,
                                user = appModel.user,
                            )
                        }

                    is AppModel.App -> {
                        if (appModel.user != Process.myUserHandle()) {
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else if (requireContext().isSystemApp(appModel.appPackage, appModel.user)) {
                            requireContext().showToast(getString(R.string.system_app_cannot_delete))
                            openAppInfo(requireContext(), appModel.user, appModel.appPackage)
                        } else {
                            requireContext().uninstall(appModel.appPackage)
                        }
                    }
                }
                viewModel.getAppList()
            },
            appHideListener = { appModel, position ->
                if (appModel is AppModel.PinnedShortcut) {
                    requireContext().showToast("Hiding pinned shortcuts is not supported")
                    return@AppDrawerAdapter
                }
                adapter.appFilteredList.removeAt(position)
                adapter.notifyItemRemoved(position)
                adapter.appsList.remove(appModel)

                val newSet = mutableSetOf<String>()
                newSet.addAll(prefs.hiddenApps)
                if (flag == Constants.FLAG_HIDDEN_APPS)
                    newSet.remove(appModel.appPackage + "|" + appModel.user.toString())
                else
                    newSet.add(appModel.appPackage + "|" + appModel.user.toString())

                prefs.hiddenApps = newSet
                if (newSet.isEmpty() && !embedded)
                    findNavController().popBackStack()
                viewModel.getAppList()
                viewModel.getHiddenApps()
            },
            appRenameListener = { appModel, renameLabel ->
                val identifier = when (appModel) {
                    is AppModel.PinnedShortcut -> appModel.identity
                    is AppModel.App -> appModel.appPackage
                    else -> return@AppDrawerAdapter
                }
                prefs.setAppRenameLabel(identifier, renameLabel)
                viewModel.getAppList()
            },
            privateSpaceToggleListener = {
                viewModel.togglePrivateSpaceLock()
            },
            privateSpaceSettingsListener = {
                viewModel.openPrivateSpaceSettings()
                if (!embedded) findNavController().popBackStack(R.id.mainFragment, false)
            },
            appFolderListener = { appModel, anchor -> showFolderPicker(appModel, anchor) }
        )
        adapter.searchOnly = flag == Constants.FLAG_LAUNCH_APP
        if (flag == Constants.FLAG_EDIT_FOLDER)
            adapter.isChecked = { FolderApp.keyOf(it) in folderMembers }

        linearLayoutManager = object : LinearLayoutManager(requireContext()) {
            override fun scrollVerticallyBy(
                dx: Int,
                recycler: Recycler,
                state: RecyclerView.State,
            ): Int {
                val scrollRange = super.scrollVerticallyBy(dx, recycler, state)
                val overScroll = dx - scrollRange
                if (!embedded && overScroll < -10 && binding.recyclerView.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
                    findNavController().popBackStack()
                return scrollRange
            }
        }

        binding.recyclerView.layoutManager = linearLayoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(getRecyclerViewOnScrollListener())
        binding.recyclerView.itemAnimator = null
        if (requireContext().isEinkDisplay())
            binding.recyclerView.overScrollMode = View.OVER_SCROLL_NEVER
        else if (!embedded && requireContext().isSystemAnimationsDisabled().not())
            binding.recyclerView.layoutAnimation =
                AnimationUtils.loadLayoutAnimation(requireContext(), R.anim.layout_anim_from_bottom)
    }

    private fun initObservers() {
        viewModel.firstOpen.observe(viewLifecycleOwner) {
        }
        if (flag == Constants.FLAG_HIDDEN_APPS) {
            viewModel.hiddenApps.observe(viewLifecycleOwner) {
                it?.let {
                    adapter.setAppList(it.toMutableList())
                }
            }
        } else {
            viewModel.appList.observe(viewLifecycleOwner) {
                currentAppList = it
                updateCombinedAppList()
            }
            if (flag == Constants.FLAG_LAUNCH_APP) {
                viewModel.privateSpaceAvailable.observe(viewLifecycleOwner) {
                    currentPrivateSpaceAvailable = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceLocked.observe(viewLifecycleOwner) {
                    currentPrivateSpaceLocked = it
                    updateCombinedAppList()
                }
                viewModel.privateSpaceApps.observe(viewLifecycleOwner) {
                    currentPrivateSpaceApps = it
                    updateCombinedAppList()
                }
            }
        }
    }

    private fun updateCombinedAppList() {
        val apps = currentAppList ?: return
        val combined = apps.toMutableList()

        if (flag == Constants.FLAG_LAUNCH_APP && currentPrivateSpaceAvailable) {
            combined.add(AppModel.PrivateSpaceHeader(isLocked = currentPrivateSpaceLocked))
            if (!currentPrivateSpaceLocked) {
                currentPrivateSpaceApps?.let { combined.addAll(it) }
            }
        }

        adapter.setAppList(combined)
        adapter.filter.filter(binding.search.query)
    }

    private fun initClickListeners() {
        binding.newFolder.setOnClickListener { showNewFolderDialog() }
        binding.appRename.setOnClickListener {
            val name = binding.search.query.toString().trim()
            if (name.isEmpty()) {
                requireContext().showToast(getString(R.string.type_a_new_app_name_first))
                binding.search.showKeyboard()
                return@setOnClickListener
            }

            when (flag) {
                Constants.FLAG_SET_HOME_APP_1 -> prefs.appName1 = name
                Constants.FLAG_SET_HOME_APP_2 -> prefs.appName2 = name
                Constants.FLAG_SET_HOME_APP_3 -> prefs.appName3 = name
                Constants.FLAG_SET_HOME_APP_4 -> prefs.appName4 = name
                Constants.FLAG_SET_HOME_APP_5 -> prefs.appName5 = name
                Constants.FLAG_SET_HOME_APP_6 -> prefs.appName6 = name
                Constants.FLAG_SET_HOME_APP_7 -> prefs.appName7 = name
                Constants.FLAG_SET_HOME_APP_8 -> prefs.appName8 = name
            }
            findNavController().popBackStack()
        }
    }

    // Search commands

    private fun updateCommands(query: String) {
        if (flag != Constants.FLAG_LAUNCH_APP) return
        val canReadContacts = ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        commands = parseSearchCommands(requireContext(), query, canReadContacts)

        // Reuse rows so typing within a command only changes text, not layout
        val layout = binding.commandsLayout
        while (layout.childCount > commands.size) layout.removeViewAt(layout.childCount - 1)
        while (layout.childCount < commands.size) layout.addView(createCommandRow())
        layout.visibility = if (commands.isEmpty()) View.GONE else View.VISIBLE
        commands.forEachIndexed { i, command ->
            (layout.getChildAt(i) as TextView).apply {
                text = commandLabel(command, isFirst = i == 0, color = currentTextColor)
                setOnClickListener { runCommand(command) }
            }
        }
    }

    private fun createCommandRow() = TextView(requireContext(), null, 0, R.style.AppName).apply {
        gravity = prefs.appLabelAlignment
        maxLines = 1
        val padding = resources.getDimensionPixelSize(R.dimen.app_padding_vertical)
        setPadding(24.dpToPx(), padding, 24.dpToPx(), padding)
    }

    // Verb in a softer tone, then the target; the top row gets a faint ↵ since enter runs it
    private fun commandLabel(command: SearchCommand, isFirst: Boolean, color: Int): CharSequence {
        val soft = ColorUtils.setAlphaComponent(color, 150)
        val faint = ColorUtils.setAlphaComponent(color, 90)
        val label = SpannableStringBuilder()
        label.append(command.verb, ForegroundColorSpan(soft), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (command.target.isNotEmpty()) label.append(" ").append(command.target)
        if (isFirst) label.append("  ↵", ForegroundColorSpan(faint), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return label
    }

    private fun runCommand(command: SearchCommand) {
        if (command.verb == VERB_ALLOW_CONTACTS) {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        command.run(requireContext())
        if (command.closesDrawer) {
            if (embedded) viewModel.closeAppDrawer.call()
            else findNavController().popBackStack(R.id.mainFragment, false)
        }
    }

    // Folders

    // Picking a home app: name a new folder for this slot, then choose its apps
    private fun showNewFolderDialog() {
        val slot = flag
        dialog?.dismiss()
        dialog = requireContext().createFolderNameDialog(
            title = R.string.new_folder,
            action = R.string.create,
        ) { name ->
            val folder = Folder.create(name)
            prefs.saveFolder(folder)
            prefs.clearHomeApp(slot)
            prefs.setHomeFolderId(slot, folder.id)
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(
                    Constants.Key.FLAG to Constants.FLAG_EDIT_FOLDER,
                    Constants.Key.FOLDER_ID to folder.id
                ),
                NavOptions.Builder().setPopUpTo(R.id.appListFragment, true).build()
            )
        }.also { it.showRespectingStatusBar() }
    }

    private fun toggleFolderApp(appModel: AppModel) {
        if (appModel.appPackage.isEmpty()) return
        if (appModel !is AppModel.App) {
            requireContext().showToast(getString(R.string.shortcuts_not_in_folders))
            return
        }
        val folder = folderId?.let { prefs.getFolder(it) } ?: return
        val updated = folder.toggle(appModel)
        prefs.saveFolder(updated)
        folderMembers.clear()
        updated.apps.mapTo(folderMembers) { it.key }
        adapter.notifyDataSetChanged()
    }

    // Long-press strip in the drawer: add the app to a folder or take it out
    private fun showFolderPicker(appModel: AppModel, anchor: View) {
        if (appModel !is AppModel.App) return
        val folders = prefs.folders
        if (folders.isEmpty()) {
            requireContext().showToast(getString(R.string.no_folders_yet))
            return
        }
        anchor.showPopupMenu(configure = { menu ->
            folders.forEachIndexed { i, folder ->
                val title = folder.name.lowercase() + "/" + if (folder.contains(appModel)) "  ✓" else ""
                menu.add(0, i, i, title)
            }
        }) { item ->
            val folder = folders[item.itemId].toggle(appModel)
            prefs.saveFolder(folder)
            val message = if (folder.contains(appModel)) R.string.added_to_folder else R.string.removed_from_folder
            requireContext().showToast(getString(message, folder.name.lowercase()))
            adapter.notifyDataSetChanged()
        }
    }

    private fun getRecyclerViewOnScrollListener(): RecyclerView.OnScrollListener {
        return object : RecyclerView.OnScrollListener() {

            var onTop = false

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                when (newState) {

                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        onTop = !recyclerView.canScrollVertically(-1)
                        if (onTop)
                            binding.search.hideKeyboard()
                    }

                    RecyclerView.SCROLL_STATE_IDLE -> {
                        if (!recyclerView.canScrollVertically(1))
                            binding.search.hideKeyboard()
                        else if (!recyclerView.canScrollVertically(-1))
                            if (!onTop && isRemoving.not() && (!embedded || isDrawerOpen))
                                binding.search.showKeyboard(prefs.autoShowKeyboard)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        cachedIsCjkKeyboard = null
        if (!embedded) binding.search.showKeyboard(prefs.autoShowKeyboard)
    }

    fun canScrollUp(): Boolean = _binding?.recyclerView?.canScrollVertically(-1) ?: false

    fun onDrawerDragStart() {
        _binding?.search?.hideKeyboard()
    }

    fun onDrawerOpened() {
        isDrawerOpen = true
        _binding?.search?.showKeyboard(prefs.autoShowKeyboard)
    }

    fun onDrawerClosed() {
        isDrawerOpen = false
        val binding = _binding ?: return
        binding.search.hideKeyboard()
        binding.search.setQuery("", false)
        binding.recyclerView.scrollToPosition(0)
    }

    override fun onStop() {
        binding.search.hideKeyboard()
        super.onStop()
    }

    override fun onDestroyView() {
        dialog?.dismiss()
        dialog = null
        super.onDestroyView()
        searchTextView = null
        _binding = null
    }
}
