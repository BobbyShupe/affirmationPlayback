package com.example.affirmationPlayback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.View
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.affirmationPlayback.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var recorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private val recordings = mutableListOf<File>()
    private lateinit var adapter: RecordingAdapter
    private var isPlaylistActive = false

    private val TAG = "AffirmMain"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRecording()
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(EXTRA_STATE) ?: return

            when (state) {
                STATE_PLAYING -> {
                    val trackName = intent.getStringExtra(EXTRA_TRACK_NAME) ?: ""
                    val current = intent.getIntExtra(EXTRA_CURRENT_INDEX, -1)
                    val total = intent.getIntExtra(EXTRA_TOTAL_COUNT, 0)
                    val filePath = intent.getStringExtra(EXTRA_FILE_PATH)

                    binding.tvStatus.text = if (total > 1) {
                        "Playing (${current + 1}/$total): $trackName"
                    } else {
                        "Playing: $trackName"
                    }

                    filePath?.let {
                        val file = File(it)
                        val pos = recordings.indexOf(file)
                        if (pos >= 0) {
                            adapter.updateSelection(pos)
                            binding.recyclerView.smoothScrollToPosition(pos)
                        }
                    }

                    isPlaylistActive = total > 1
                    binding.btnPlayAll.text = "Stop Playback"
                }

                STATE_COMPLETED -> {
                    binding.tvStatus.text = "Playback finished"
                    adapter.clearSelection()
                    isPlaylistActive = false
                    binding.btnPlayAll.text = "Stop Playback"   // or change to "Clear" if you want

                    // DO NOT call stopPlaybackService() here
                }

                STATE_STOPPED -> {
                    binding.tvStatus.text = "Ready"
                    adapter.clearSelection()
                    isPlaylistActive = false
                    binding.btnPlayAll.text = "Play All"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = RecordingAdapter(
            recordings = recordings,
            onPlay = { file -> startServiceForSingle(file) },
            onRename = { file -> showRenameDialog(file) },
            onDelete = { file ->
                deleteRecording(file)
                adapter.clearSelection()
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.btnNewPlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }

        binding.btnRecord.setOnClickListener {
            if (hasMicPermission()) {
                startRecording()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnStop.setOnClickListener {
            stopRecording()
        }

        binding.btnPlayAll.setOnClickListener {
            if (isPlaylistActive) {
                stopPlaybackService()
            } else {
                startPlaylistService()
            }
        }

        binding.btnViewPlaylists.setOnClickListener {
            // TODO: implement playlist viewing if needed
            Toast.makeText(this, "Playlist feature coming soon", Toast.LENGTH_SHORT).show()
        }

        loadRecordings()

        // Register local broadcast receiver
        val filter = IntentFilter(ACTION_PLAYBACK_STATE)
        LocalBroadcastManager.getInstance(this).registerReceiver(playbackReceiver, filter)
    }

    private fun startServiceForSingle(file: File) {
        if (isPlaylistActive) {
            Toast.makeText(this, "Stop current playback first", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_START_PLAYLIST
            putStringArrayListExtra(PlaybackService.EXTRA_FILE_PATHS, arrayListOf(file.absolutePath))
            putExtra(PlaybackService.EXTRA_SHUFFLE, false)
            putExtra(PlaybackService.EXTRA_DELAY_SECONDS, 0)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startPlaylistService() {
        if (recordings.isEmpty()) {
            Toast.makeText(this, "No recordings to play", Toast.LENGTH_SHORT).show()
            return
        }

        val paths = ArrayList(recordings.map { it.absolutePath })
        val delay = binding.etDelaySeconds.text.toString().toIntOrNull() ?: 0
        val shuffle = binding.switchShuffle.isChecked

        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_START_PLAYLIST
            putStringArrayListExtra(PlaybackService.EXTRA_FILE_PATHS, paths)
            putExtra(PlaybackService.EXTRA_SHUFFLE, shuffle)
            putExtra(PlaybackService.EXTRA_DELAY_SECONDS, delay)
        }

        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_STOP
        }
        startService(intent)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun getRecordingsDir(): File =
        File(filesDir, "affirmations").also { dir ->
            if (!dir.exists()) dir.mkdirs()
        }

    private fun loadRecordings() {
        recordings.clear()
        val files = getRecordingsDir().listFiles() ?: emptyArray()
        recordings.addAll(files.sortedBy { it.lastModified() })
        adapter.notifyDataSetChanged()
        updateUIState()
    }

    private fun updateUIState() {
        if (recordings.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
            binding.btnPlayAll.isEnabled = false
            binding.tvStatus.text = "No affirmations yet"
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            binding.btnPlayAll.isEnabled = true
            binding.tvStatus.text = "${recordings.size} affirmation${if (recordings.size != 1) "s" else ""}"
        }
    }

    private fun startRecording() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        currentRecordingFile = File(getRecordingsDir(), "affirm_$timestamp.m4a")

        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(currentRecordingFile?.absolutePath)

            try {
                prepare()
                start()
                binding.tvStatus.text = "Recording…"
                binding.btnRecord.isEnabled = false
                binding.btnStop.isEnabled = true
            } catch (e: Exception) {
                Log.e(TAG, "startRecording failed", e)
                Toast.makeText(this@MainActivity, "Failed to start recording", Toast.LENGTH_LONG).show()
                releaseRecorder()
            }
        }
    }

    private fun stopRecording() {
        releaseRecorder()

        currentRecordingFile?.let { file ->
            recordings.add(file)
            adapter.notifyItemInserted(recordings.size - 1)
            binding.recyclerView.scrollToPosition(recordings.size - 1)
            updateUIState()
        }

        currentRecordingFile = null
        binding.tvStatus.text = "Ready"
        binding.btnRecord.isEnabled = true
        binding.btnStop.isEnabled = false
    }

    private fun releaseRecorder() {
        recorder?.apply {
            try { stop() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        recorder = null
    }

    private fun showRenameDialog(file: File) {
        val input = TextInputEditText(this).apply { setText(file.nameWithoutExtension) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Rename affirmation")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text?.toString()?.trim() ?: return@setPositiveButton
                if (newName.isNotBlank()) {
                    val parentDir = file.parentFile ?: getRecordingsDir()
                    var uniqueFile = File(parentDir, "$newName.m4a")

                    if (uniqueFile.exists() && uniqueFile.absolutePath != file.absolutePath) {
                        var counter = 1
                        while (uniqueFile.exists()) {
                            uniqueFile = File(parentDir, "$newName ($counter).m4a")
                            counter++
                        }
                    }

                    if (file.renameTo(uniqueFile)) {
                        loadRecordings()
                    } else {
                        Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteRecording(file: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete?")
            .setMessage("Delete \"${file.nameWithoutExtension}\" ?")
            .setPositiveButton("Delete") { _, _ ->
                if (file.delete()) {
                    val pos = recordings.indexOf(file)
                    if (pos >= 0) {
                        recordings.removeAt(pos)
                        adapter.notifyItemRemoved(pos)
                        updateUIState()
                    }
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreatePlaylistDialog() {
        Toast.makeText(this, "Playlist feature coming soon", Toast.LENGTH_SHORT).show()
        // Implement later if needed
    }

    override fun onDestroy() {
        releaseRecorder()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(playbackReceiver)
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAYBACK_STATE = "com.example.affirmationPlayback.ACTION_PLAYBACK_STATE"
        const val EXTRA_STATE = "extra_state"
        const val EXTRA_TRACK_NAME = "extra_track_name"
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_CURRENT_INDEX = "extra_current_index"
        const val EXTRA_TOTAL_COUNT = "extra_total_count"

        const val STATE_PLAYING = "playing"
        const val STATE_STOPPED = "stopped"
        const val STATE_COMPLETED = "completed"
    }
}