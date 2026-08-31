package com.example.multiview.ui

import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.RadioButton
import android.widget.RadioGroup
import com.example.multiview.R
import com.example.multiview.data.ProfileMode
import com.example.multiview.utils.UrlUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText

/**
 * The home screen: one place to decide what opens.
 *
 * The user types or pastes a URL (left blank for empty screens), picks how
 * many screens to create, and chooses whether each screen gets its own
 * isolated login. The offered screen counts stop at the device's RAM-based
 * cap, so the sheet can never promise screens the phone cannot hold.
 */
class HomeSheet(
    context: Context,
    paneCap: Int,
    defaultIsolate: Boolean,
    private val onOpen: (url: String, count: Int, mode: ProfileMode) -> Unit,
) : BottomSheetDialog(context) {

    private val counts = listOf(1, 2, 3, 4, 6, 8, 12).filter { it <= paneCap }

    init {
        val root = LayoutInflater.from(context).inflate(R.layout.sheet_home, null)
        setContentView(root)

        val input = root.findViewById<TextInputEditText>(R.id.etHomeUrl)
        root.findViewById<MaterialButton>(R.id.btnPaste).setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val text = cm?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
            if (text.isNotEmpty()) input.setText(text)
        }

        val chips = root.findViewById<ChipGroup>(R.id.chipScreens)
        counts.forEachIndexed { i, n ->
            val chip = Chip(context).apply {
                id = android.view.View.generateViewId()
                text = n.toString()
                isCheckable = true
                isChecked = i == 0
            }
            chips.addView(chip)
        }

        val rg = root.findViewById<RadioGroup>(R.id.rgMode)
        rg.check(if (defaultIsolate) R.id.rbIsolated else R.id.rbShared)

        root.findViewById<MaterialButton>(R.id.btnOpen).setOnClickListener {
            val typed = input.text?.toString()?.trim().orEmpty()
            val url = if (typed.isEmpty()) "" else UrlUtils.normalize(typed)
            val selected = chips.checkedChipId
            val count = (0 until chips.childCount)
                .map { chips.getChildAt(it) as Chip }
                .firstOrNull { it.id == selected }
                ?.text?.toString()?.toIntOrNull() ?: 1
            val mode = if (root.findViewById<RadioButton>(R.id.rbIsolated).isChecked) {
                ProfileMode.ISOLATED
            } else {
                ProfileMode.SHARED
            }
            dismiss()
            onOpen(url, count, mode)
        }
    }
}
