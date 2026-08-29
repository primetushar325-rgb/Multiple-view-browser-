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
import com.example.multiview.panes.LayoutResolver
import com.google.android.material.bottomsheet.BottomSheetDialog

/** Bottom sheet listing every layout; the active one shows a check. */
class LayoutPickerSheet(
    context: Context,
    private val currentLayoutId: String,
    private val onLayoutChosen: (String) -> Unit,
) : BottomSheetDialog(context) {

    init {
        val root = LayoutInflater.from(context).inflate(R.layout.sheet_layout_picker, null)
        setContentView(root)
        val list = root.findViewById<RecyclerView>(R.id.rvLayouts)
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = LayoutAdapter(currentLayoutId) { id ->
            dismiss()
            onLayoutChosen(id)
        }
    }

    private class LayoutAdapter(
        private val current: String,
        private val onClick: (String) -> Unit,
    ) : RecyclerView.Adapter<LayoutAdapter.VH>() {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.tvName)
            val check: ImageView = v.findViewById(R.id.ivCheck)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_layout_option, parent, false)
        )

        override fun getItemCount(): Int = LayoutResolver.LAYOUTS.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (id, label) = LayoutResolver.LAYOUTS[position]
            holder.name.text = label
            holder.check.visibility = if (id == current) View.VISIBLE else View.INVISIBLE
            holder.itemView.setOnClickListener { onClick(id) }
        }
    }
}
