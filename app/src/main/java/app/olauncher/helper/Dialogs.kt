package app.olauncher.helper

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.annotation.MenuRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import app.olauncher.data.Prefs
import app.olauncher.databinding.DialogBaseBinding
import app.olauncher.databinding.DialogFolderNameBinding

/**
 * Shows a popup menu hanging off the end edge of this view.
 * [configure] can add or tweak items before the menu is shown.
 */
fun View.showPopupMenu(
    @MenuRes menuRes: Int = 0,
    configure: (Menu) -> Unit = {},
    onItemClick: (MenuItem) -> Unit,
): PopupMenu {
    val popup = PopupMenu(context, this, Gravity.END)
    if (menuRes != 0) popup.menuInflater.inflate(menuRes, popup.menu)
    configure(popup.menu)
    popup.setOnMenuItemClickListener { item ->
        onItemClick(item)
        true
    }
    popup.show()
    return popup
}

/**
 * App dialog: shows without bringing back a hidden status bar, and blurs the
 * screen behind it on Android 12+, fading blur and dialog out together on dismiss.
 */
class OlDialog(context: Context) : AlertDialog(context) {

    private var blur: WindowBlur? = null

    fun showRespectingStatusBar() {
        val window = window
        if (window == null || Prefs(context).showStatusBar) {
            show()
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            show()
            window.hideStatusBar()
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        blur = window?.let { WindowBlur(it).apply { fadeIn() } }
    }

    override fun dismiss() {
        val blur = blur ?: return super.dismiss()
        this.blur = null
        blur.fadeOut { super.dismiss() }
    }
}

/**
 * Builds a dialog using the app's own layout: a title row with a close icon,
 * an optional [message] or custom [content], and a text [action] at the end.
 * [content] receives the container so the inflated view keeps its XML margins.
 */
fun Context.createDialog(
    @StringRes title: Int,
    @StringRes action: Int,
    @StringRes message: Int = 0,
    @StringRes neutral: Int = 0,
    onNeutral: () -> Unit = {},
    onAction: () -> Unit = {},
    content: ((ViewGroup) -> View)? = null,
): OlDialog {
    val dialog = OlDialog(this)
    val binding = DialogBaseBinding.inflate(LayoutInflater.from(dialog.context))
    binding.tvTitle.setText(title)
    binding.tvAction.setText(action)
    if (message != 0) {
        binding.tvMessage.setText(message)
        binding.tvMessage.isVisible = true
    }
    if (neutral != 0) {
        binding.tvNeutral.setText(neutral)
        binding.tvNeutral.isVisible = true
    }
    content?.let {
        binding.contentContainer.addView(it(binding.contentContainer))
        binding.contentContainer.isVisible = true
    }
    dialog.setView(binding.root)
    binding.ivClose.setOnClickListener { dialog.dismiss() }
    binding.tvNeutral.setOnClickListener {
        onNeutral()
        dialog.dismiss()
    }
    binding.tvAction.setOnClickListener {
        onAction()
        dialog.dismiss()
    }
    return dialog
}

/** Asks for a folder name, prefilled with [initial], and passes on a non-blank result. */
fun Context.createFolderNameDialog(
    @StringRes title: Int,
    @StringRes action: Int,
    initial: String = "",
    onName: (String) -> Unit,
): OlDialog {
    var input: EditText? = null
    val submit = { input?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(onName) }
    val dialog = createDialog(
        title = title,
        action = action,
        onAction = { submit() },
        content = { parent ->
            DialogFolderNameBinding.inflate(LayoutInflater.from(parent.context), parent, false).etFolderName.also {
                input = it
                it.setText(initial)
                it.setSelection(initial.length)
            }
        },
    )
    input?.setOnEditorActionListener { _, actionId, _ ->
        if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
        submit()
        dialog.dismiss()
        true
    }
    dialog.setOnShowListener { input?.post { input?.showKeyboard() } }
    return dialog
}
