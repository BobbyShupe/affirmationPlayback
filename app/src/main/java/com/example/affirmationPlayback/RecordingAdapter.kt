package com.example.affirmationPlayback

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import com.example.affirmationPlayback.databinding.ItemRecordingBinding


class RecordingAdapter(
    private val recordings: List<File>,
    private val onPlay: (File) -> Unit,
    private val onRename: (File) -> Unit,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<RecordingAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = recordings[position]
        holder.binding.tvName.text = file.nameWithoutExtension

        holder.binding.btnPlay.setOnClickListener { onPlay(file) }
        holder.binding.btnRename.setOnClickListener { onRename(file) }
        holder.binding.btnDelete.setOnClickListener { onDelete(file) }
    }

    override fun getItemCount() = recordings.size
}