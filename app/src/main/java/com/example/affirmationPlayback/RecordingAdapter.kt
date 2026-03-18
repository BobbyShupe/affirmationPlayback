package com.example.affirmationPlayback

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.affirmationPlayback.databinding.ItemRecordingBinding
import java.io.File

class RecordingAdapter(
    private val recordings: List<File>,
    private val onPlay: (File) -> Unit,
    private val onRename: (File) -> Unit,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<RecordingAdapter.ViewHolder>() {

    private var selectedPosition: Int = RecyclerView.NO_POSITION

    inner class ViewHolder(val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition     // ← changed
                if (pos != RecyclerView.NO_POSITION) {
                    val prev = selectedPosition
                    selectedPosition = pos
                    notifyItemChanged(prev)
                    notifyItemChanged(pos)
                    onPlay(recordings[pos])
                }
            }

            binding.root.setOnLongClickListener {
                val pos = bindingAdapterPosition      // ← changed
                if (pos != RecyclerView.NO_POSITION) {
                    val file = recordings[pos]

                    val popup = PopupMenu(binding.root.context, binding.root)
                    popup.menu.add("Rename")
                    popup.menu.add("Delete")

                    popup.setOnMenuItemClickListener { item ->
                        when (item.title) {
                            "Play" -> {
                                onPlay(file)
                                true
                            }
                            "Rename" -> {
                                onRename(file)
                                true
                            }
                            "Delete" -> {
                                onDelete(file)
                                true
                            }
                            else -> false
                        }
                    }

                    popup.show()
                    true
                } else {
                    false
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = recordings[position]
        holder.binding.tvName.text = file.nameWithoutExtension

        // Highlight selected item
        if (position == selectedPosition) {
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, R.color.selected_item_background)
            )
            holder.binding.tvName.setTextColor(
                ContextCompat.getColor(holder.itemView.context, R.color.selected_item_text)
            )
        } else {
            holder.itemView.setBackgroundColor(
                ContextCompat.getColor(holder.itemView.context, android.R.color.transparent)
            )
            holder.binding.tvName.setTextColor(
                ContextCompat.getColor(holder.itemView.context, R.color.normal_item_text)
            )
        }
    }

    override fun getItemCount() = recordings.size

    fun clearSelection() {
        val oldPos = selectedPosition
        selectedPosition = RecyclerView.NO_POSITION
        if (oldPos != RecyclerView.NO_POSITION) notifyItemChanged(oldPos)
    }

    // Add this inside the RecordingAdapter class
    fun updateSelection(position: Int) {
        val oldPos = selectedPosition
        selectedPosition = position

        // Notify the change to both the old and new items to update their background colors
        if (oldPos != RecyclerView.NO_POSITION) notifyItemChanged(oldPos)
        if (selectedPosition != RecyclerView.NO_POSITION) notifyItemChanged(selectedPosition)
    }
}