package com.example.affirmationPlayback

import android.view.View
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.affirmationPlayback.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val playlistNames = mutableSetOf<String>()
    private val playlists = mutableMapOf<String, MutableList<String>>()
    private lateinit var binding: ActivityMainBinding
    private var recorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private val recordings = mutableListOf<File>()
    private lateinit var adapter: RecordingAdapter

    private var mediaPlayer: MediaPlayer? = null
    private var isPlayingPlaylist = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = RecordingAdapter(
            recordings = recordings,
            onPlay = { file -> playSingle(file) },
            onRename = { file -> showRenameDialog(file) },
            onDelete = { file ->

                deleteRecording(file)
                adapter.clearSelection()           // optional: remove highlight
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
            if (recordings.isNotEmpty()) {
                playPlaylist()
            } else {
                Toast.makeText(this, "No recordings to play", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnViewPlaylists.setOnClickListener {
            if (playlistNames.isEmpty()) {
                Toast.makeText(this, "No playlists yet", Toast.LENGTH_SHORT).show()
            } else {
                val namesArray = playlistNames.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Your Playlists")
                    .setItems(namesArray) { dialog, which ->
                        val selectedName = namesArray[which]
                        Toast.makeText(this, "Selected: $selectedName", Toast.LENGTH_SHORT).show()
                        // Later: open that playlist
                    }
                    .setPositiveButton("Close", null)
                    .show()
            }
        }

        loadRecordings()
    }

    private fun showCreatePlaylistDialog() {
        val input = TextInputEditText(this).apply {
            hint = "Playlist name (e.g. Morning Motivation)"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Create new playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim() ?: ""
                if (name.isNotEmpty()) {

                    playlists[name] = mutableListOf()
                    Toast.makeText(this, "Playlist '$name' created", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createNewPlaylist(name: String) {
        // Option A: Just remember the name (simple, in-memory)
        // playlists.add(name)
        // adapter.notifyDataSetChanged()

        // Option B: Save to SharedPreferences or file (persistent)
        val prefs = getSharedPreferences("playlists", MODE_PRIVATE)
        val existing = prefs.getStringSet("playlist_names", mutableSetOf()) ?: mutableSetOf()
        existing.add(name)
        prefs.edit().putStringSet("playlist_names", existing).apply()

        Toast.makeText(this, "Playlist '$name' created", Toast.LENGTH_SHORT).show()

        // Optional: switch to this playlist view or refresh list
        // loadPlaylists()
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
        recordings.addAll(files.sortedBy { it.lastModified() })  // oldest first
        adapter.notifyDataSetChanged()
        updatePlaylistVisibility()
    }

    private fun updatePlaylistVisibility() {
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
            recordings.add(file)          // ← adds to the end (bottom)
            adapter.notifyItemInserted(recordings.size - 1)
            binding.recyclerView.scrollToPosition(recordings.size - 1)
            updatePlaylistVisibility()
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

    private fun playSingle(file: File) {
        releasePlayer()
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                prepare()
                start()
                binding.tvStatus.text = "Playing: ${file.nameWithoutExtension}"
                setOnCompletionListener {
                    releasePlayer()
                    binding.tvStatus.text = "Ready"
                }
            } catch (e: Exception) {
                Log.e(TAG, "playSingle failed", e)
                Toast.makeText(this@MainActivity, "Cannot play this file", Toast.LENGTH_SHORT).show()
                releasePlayer()
            }
        }
    }

    private fun playPlaylist() {
        if (isPlayingPlaylist) {
            releasePlayer()
            isPlayingPlaylist = false
            binding.btnPlayAll.text = "Play Playlist"
            binding.tvStatus.text = "Ready"
            return
        }

        isPlayingPlaylist = true
        binding.btnPlayAll.text = "Stop Playlist"
        playNextInPlaylist(0)
    }

    private fun playNextInPlaylist(index: Int) {
        if (index >= recordings.size || !isPlayingPlaylist) {
            isPlayingPlaylist = false
            binding.btnPlayAll.text = "Play Playlist"
            binding.tvStatus.text = "Ready"
            releasePlayer()
            return
        }

        val file = recordings[index]
        binding.tvStatus.text = "Playing (${index + 1}/${recordings.size}): ${file.nameWithoutExtension}"

        releasePlayer()
        mediaPlayer = MediaPlayer().apply {
            try {
                setDataSource(file.absolutePath)
                prepareAsync()
                setOnPreparedListener { start() }
                setOnCompletionListener { playNextInPlaylist(index + 1) }
                setOnErrorListener { _, _, _ ->
                    playNextInPlaylist(index + 1)
                    true
                }
            } catch (e: Exception) {
                playNextInPlaylist(index + 1)
            }
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.apply {
            try { stop() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        mediaPlayer = null
    }

    private fun showRenameDialog(file: File) {
        val input = TextInputEditText(this).apply { setText(file.nameWithoutExtension) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Rename affirmation")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text?.toString()?.trim() ?: return@setPositiveButton
                if (newName.isNotBlank()) {
                    val newFile = File(file.parentFile!!, "$newName.m4a")
                    if (file.renameTo(newFile)) {
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
                        updatePlaylistVisibility()
                    }
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Delete failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        releaseRecorder()
        releasePlayer()
        super.onDestroy()
    }
}