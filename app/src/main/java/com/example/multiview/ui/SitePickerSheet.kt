package com.example.multiview.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.multiview.R
import com.example.multiview.panes.SitePresets
import com.example.multiview.utils.UrlUtils
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/** Bottom sheet that loads a preset (or a typed URL) into the chosen pane. */
class SitePickerSheet(
    context: Context,
    private val onSiteChosen: (String) -> Unit,
) : BottomSheetDialog(context) {

    init {
        val root = LayoutInflater.from(context).inflate(R.layout.sheet_site_picker, null)
        setContentView(root)

        val list = root.findViewById<RecyclerView>(R.id.rvPresets)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = PresetAdapter { preset ->
            dismiss()
            onSiteChosen(UrlUtils.presetToUrl(preset.url))
        }

        val input = root.findViewById<TextInputEditText>(R.id.etCustomUrl)
        root.findViewById<MaterialButton>(R.id.btnGo).setOnClickListener {
            val typed = input.text?.toString()?.trim().orEmpty()
            if (typed.isNotEmpty()) {
                dismiss()
                onSiteChosen(UrlUtils.normalize(typed))
            }
        }
    }

    private class PresetAdapter(
        private val onClick: (com.example.multiview.panes.SitePreset) -> Unit,
    ) : RecyclerView.Adapter<PresetAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.ivIcon)
            val name: TextView = v.findViewById(R.id.tvName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_site_preset, parent, false)
        )

        override fun getItemCount(): Int = SitePresets.ALL.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val preset = SitePresets.ALL[position]
            holder.icon.setImageResource(preset.iconRes)
            holder.name.text = preset.name
            holder.itemView.setOnClickListener { onClick(preset) }
        }
    }
}
