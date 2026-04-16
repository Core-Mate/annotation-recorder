package com.annotation.recorder.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.annotation.recorder.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SessionFileAdapter(
    private val onSelectionChanged: (Int) -> Unit,
    private val onOpenRequested: (File) -> Unit
) : RecyclerView.Adapter<SessionFileAdapter.SessionViewHolder>() {

    private val files = mutableListOf<File>()
    private val selectedPaths = linkedSetOf<String>()
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun submitFiles(items: List<File>) {
        files.clear()
        files.addAll(items)
        val currentPaths = files.mapTo(mutableSetOf()) { it.absolutePath }
        selectedPaths.retainAll(currentPaths)
        notifyDataSetChanged()
        onSelectionChanged(selectedPaths.size)
    }

    fun getSelectedFiles(): List<File> {
        if (selectedPaths.isEmpty()) return emptyList()
        return files.filter { selectedPaths.contains(it.absolutePath) }
    }

    fun clearSelection() {
        selectedPaths.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SessionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_session_file, parent, false)
        return SessionViewHolder(view)
    }

    override fun onBindViewHolder(holder: SessionViewHolder, position: Int) {
        holder.bind(files[position])
    }

    override fun getItemCount(): Int = files.size

    inner class SessionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkSession: CheckBox = itemView.findViewById(R.id.checkSession)
        private val textFileName: TextView = itemView.findViewById(R.id.textFileName)
        private val textFileMeta: TextView = itemView.findViewById(R.id.textFileMeta)
        private val layoutContent: View = itemView.findViewById(R.id.layoutContent)

        fun bind(file: File) {
            textFileName.text = file.name
            val date = timeFormat.format(Date(file.lastModified()))
            val sizeKb = (file.length() / 1024L).coerceAtLeast(1L)
            textFileMeta.text = "$date · ${sizeKb}KB"

            checkSession.setOnCheckedChangeListener(null)
            checkSession.isChecked = selectedPaths.contains(file.absolutePath)
            checkSession.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedPaths.add(file.absolutePath)
                } else {
                    selectedPaths.remove(file.absolutePath)
                }
                onSelectionChanged(selectedPaths.size)
            }

            layoutContent.setOnClickListener { onOpenRequested(file) }
        }
    }
}
