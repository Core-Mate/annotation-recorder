package com.annotation.recorder.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.annotation.recorder.R
import com.annotation.recorder.accessibility.RecorderAccessibilityService
import com.annotation.recorder.capture.SessionObserver
import com.annotation.recorder.capture.ScreenCaptureManager
import com.annotation.recorder.capture.SessionRecorder
import com.annotation.recorder.capture.SessionState
import com.annotation.recorder.domain.RecordingMode
import com.annotation.recorder.service.OverlayControlService
import com.annotation.recorder.service.SessionForegroundService
import com.annotation.recorder.util.PermissionUtils
import com.annotation.recorder.util.RecordingModeStore
import com.google.android.material.button.MaterialButton
import java.io.File
import android.media.projection.MediaProjectionManager
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : AppCompatActivity(), SessionObserver {

    private lateinit var accessibilityStatusText: TextView
    private lateinit var overlayStatusText: TextView
    private lateinit var sessionStatusText: TextView
    private lateinit var sessionsTitleText: TextView
    private lateinit var outputPathText: TextView

    private lateinit var btnEnableAccessibility: MaterialButton
    private lateinit var btnEnableOverlay: MaterialButton
    private lateinit var btnStartOverlayControl: MaterialButton
    private lateinit var btnStartRecording: MaterialButton
    private lateinit var btnStopRecording: MaterialButton
    private lateinit var btnOpenLastOutput: MaterialButton
    private lateinit var btnShareSelectedToFeishu: MaterialButton
    private lateinit var btnDeleteSelectedSessions: MaterialButton
    private lateinit var recyclerSessions: RecyclerView

    private lateinit var sessionFileAdapter: SessionFileAdapter

    private var lastOutputPath: String? = null
    private var renameDialogShowing = false
    private var lastRenamePromptPath: String? = null
    private var pendingStartRecordingAfterCaptureGrant = false
    private var pendingModeAfterCaptureGrant: RecordingMode = RecordingMode.MODE_1_TREE

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                ScreenCaptureManager.updatePermission(result.resultCode, result.data)
                if (pendingStartRecordingAfterCaptureGrant) {
                    startRecordingInternal(pendingModeAfterCaptureGrant)
                }
            } else if (pendingStartRecordingAfterCaptureGrant) {
                Toast.makeText(
                    this,
                    R.string.toast_capture_permission_required,
                    Toast.LENGTH_SHORT
                ).show()
                startRecordingInternal(RecordingMode.MODE_1_TREE)
            }
            pendingStartRecordingAfterCaptureGrant = false
            pendingModeAfterCaptureGrant = RecordingMode.MODE_1_TREE
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupSessionList()
        bindActions()
        requestNotificationPermissionIfNeeded()
        refreshPermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        SessionRecorder.addObserver(this)
        loadLatestOutputIfMissing()
        refreshSessionList()
        refreshOutputActions()
        maybePromptRename()
        refreshPermissionStatus()
    }

    override fun onPause() {
        SessionRecorder.removeObserver(this)
        super.onPause()
    }

    override fun onSessionStateChanged(state: SessionState, sessionId: String?, lastOutputPath: String?) {
        runOnUiThread {
            sessionStatusText.text = when (state) {
                SessionState.IDLE -> getString(R.string.session_idle)
                SessionState.RECORDING -> getString(R.string.session_recording, sessionId ?: "-")
                SessionState.STOPPING -> getString(R.string.session_stopping)
            }
            if (!lastOutputPath.isNullOrBlank()) {
                this.lastOutputPath = lastOutputPath
            }
            refreshOutputActions()
            refreshSessionList()
            btnStartRecording.isEnabled = (state == SessionState.IDLE)
            btnStopRecording.isEnabled = (state == SessionState.RECORDING)
            if (state == SessionState.IDLE) {
                maybePromptRename()
            }
        }
    }

    private fun bindViews() {
        accessibilityStatusText = findViewById(R.id.accessibilityStatusText)
        overlayStatusText = findViewById(R.id.overlayStatusText)
        sessionStatusText = findViewById(R.id.sessionStatusText)
        sessionsTitleText = findViewById(R.id.sessionsTitleText)
        outputPathText = findViewById(R.id.outputPathText)

        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnEnableOverlay = findViewById(R.id.btnEnableOverlay)
        btnStartOverlayControl = findViewById(R.id.btnStartOverlayControl)
        btnStartRecording = findViewById(R.id.btnStartRecording)
        btnStopRecording = findViewById(R.id.btnStopRecording)
        btnOpenLastOutput = findViewById(R.id.btnOpenLastOutput)
        btnShareSelectedToFeishu = findViewById(R.id.btnShareSelectedToFeishu)
        btnDeleteSelectedSessions = findViewById(R.id.btnDeleteSelectedSessions)
        recyclerSessions = findViewById(R.id.recyclerSessions)
    }

    private fun setupSessionList() {
        sessionFileAdapter = SessionFileAdapter(
            onSelectionChanged = { count ->
                btnShareSelectedToFeishu.isEnabled = count > 0
                btnDeleteSelectedSessions.isEnabled = count > 0
                updateSessionStatusTitle(count)
            },
            onOpenRequested = { file ->
                openFile(file)
            }
        )
        recyclerSessions.layoutManager = LinearLayoutManager(this)
        recyclerSessions.adapter = sessionFileAdapter
        recyclerSessions.isNestedScrollingEnabled = true
        btnDeleteSelectedSessions.isEnabled = false
    }

    private fun bindActions() {
        btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnEnableOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        btnStartOverlayControl.setOnClickListener {
            OverlayControlService.start(this)
            Toast.makeText(this, "Overlay control started", Toast.LENGTH_SHORT).show()
        }

        btnStartRecording.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, R.string.toast_need_accessibility, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showModePickerAndStart()
        }

        btnStopRecording.setOnClickListener {
            SessionRecorder.stopSessionAsync(this) {
                SessionForegroundService.stop(this)
                ensureOverlayControlRunning()
            }
        }

        outputPathText.setOnClickListener { openLastOutput() }
        btnOpenLastOutput.setOnClickListener { openLastOutput() }
        btnShareSelectedToFeishu.setOnClickListener { shareSelectedToFeishu() }
        btnDeleteSelectedSessions.setOnClickListener { confirmDeleteSelectedSessions() }
    }

    private fun refreshPermissionStatus() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        accessibilityStatusText.text = if (accessibilityEnabled) {
            getString(R.string.status_accessibility_enabled)
        } else {
            getString(R.string.status_accessibility_disabled)
        }
        btnEnableAccessibility.visibility = if (accessibilityEnabled) View.GONE else View.VISIBLE

        val overlayEnabled = Settings.canDrawOverlays(this)
        overlayStatusText.text = if (overlayEnabled) {
            getString(R.string.status_overlay_enabled)
        } else {
            getString(R.string.status_overlay_disabled)
        }
        btnEnableOverlay.visibility = if (overlayEnabled) View.GONE else View.VISIBLE
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return PermissionUtils.isAccessibilityServiceEnabled(this)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1100
            )
        }
    }

    private fun requestScreenCapturePermission() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = manager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startRecordingInternal(mode: RecordingMode) {
        RecordingModeStore.set(this, mode)
        if (SessionRecorder.startSession(this, mode)) {
            SessionForegroundService.start(this)
            ensureOverlayControlRunning()
            RecorderAccessibilityService.requestInitialSnapshot()
        }
    }

    private fun ensureOverlayControlRunning() {
        if (Settings.canDrawOverlays(this)) {
            OverlayControlService.start(this)
        }
    }

    private fun showModePickerAndStart() {
        val currentMode = RecordingModeStore.get(this)
        val labels = arrayOf(
            getString(R.string.mode_1_title),
            getString(R.string.mode_2_title)
        )
        var selected = if (currentMode == RecordingMode.MODE_2_VIDEO_SEMANTIC) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_mode_title)
            .setSingleChoiceItems(labels, selected) { _, which ->
                selected = which
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.mode_start_now) { _, _ ->
                val mode = if (selected == 1) {
                    RecordingMode.MODE_2_VIDEO_SEMANTIC
                } else {
                    RecordingMode.MODE_1_TREE
                }
                if (mode == RecordingMode.MODE_2_VIDEO_SEMANTIC && !ScreenCaptureManager.hasPermission()) {
                    pendingModeAfterCaptureGrant = mode
                    pendingStartRecordingAfterCaptureGrant = true
                    requestScreenCapturePermission()
                    return@setPositiveButton
                }
                startRecordingInternal(mode)
            }
            .show()
    }

    private fun refreshOutputActions() {
        val outputFile = resolveLastOutputFile()
        if (outputFile != null) {
            val uri = fileUri(outputFile)
            outputPathText.text = getString(R.string.session_last_output_link, uri.toString())
        } else {
            outputPathText.text = getString(R.string.session_last_output, lastOutputPath ?: "-")
        }
        outputPathText.isEnabled = outputFile != null
        btnOpenLastOutput.isEnabled = outputFile != null
    }

    private fun resolveLastOutputFile(): File? {
        val path = lastOutputPath ?: return null
        if (path.startsWith("write_failed:")) return null
        val file = File(path)
        return if (file.exists() && file.isFile) file else null
    }

    private fun loadLatestOutputIfMissing() {
        if (!lastOutputPath.isNullOrBlank()) return
        val latest = findLatestSessionFile()
        if (latest != null) {
            lastOutputPath = latest.absolutePath
        }
    }

    private fun findLatestSessionFile(): File? {
        val sessionsDir = File(getExternalFilesDir(null) ?: filesDir, "sessions")
        val files = sessionsDir.listFiles { f -> f.isFile && f.extension.equals("xml", ignoreCase = true) }
            ?: return null
        return files.maxByOrNull { it.lastModified() }
    }

    private fun fileUri(file: File): Uri {
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun openLastOutput() {
        val file = resolveLastOutputFile()
        if (file == null) {
            Toast.makeText(this, R.string.toast_output_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        openFile(file)
    }

    private fun openFile(file: File) {
        val uri = fileUri(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/xml")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_xml_viewer, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareSelectedToFeishu() {
        val selectedFiles = sessionFileAdapter.getSelectedFiles()
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_session_selected, Toast.LENGTH_SHORT).show()
            return
        }
        val uris = ArrayList<Uri>(selectedFiles.size)
        selectedFiles.forEach { uris.add(fileUri(it)) }
        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/xml"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TEXT, selectedFiles.joinToString("\n") { it.absolutePath })
            val first = uris.first()
            val cd = ClipData.newUri(contentResolver, selectedFiles.first().name, first)
            uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
            clipData = cd
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val feishuPackage = listOf("com.ss.android.lark", "com.larksuite.suite")
            .firstOrNull { isPackageInstalled(it) }
        if (feishuPackage != null) {
            shareIntent.setPackage(feishuPackage)
            uris.forEach { uri ->
                grantUriPermission(feishuPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(shareIntent)
                return
            } catch (_: ActivityNotFoundException) {
                // fallback to chooser
            }
        }

        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_output_chooser)))
    }

    private fun confirmDeleteSelectedSessions() {
        val selectedFiles = sessionFileAdapter.getSelectedFiles()
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_session_selected, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, selectedFiles.size))
            .setNegativeButton(R.string.delete_confirm_cancel, null)
            .setPositiveButton(R.string.delete_confirm_ok) { _, _ ->
                deleteSelectedSessions(selectedFiles)
            }
            .show()
    }

    private fun deleteSelectedSessions(selectedFiles: List<File>) {
        var deleted = 0
        var failed = 0
        selectedFiles.forEach { file ->
            if (file.delete()) {
                deleted++
            } else {
                failed++
            }
        }

        val latest = findLatestSessionFile()
        lastOutputPath = latest?.absolutePath
        if (lastOutputPath == null) {
            SessionRecorder.updateLastOutputPath("")
        }
        sessionFileAdapter.clearSelection()
        refreshSessionList()
        refreshOutputActions()
        Toast.makeText(
            this,
            getString(R.string.delete_result_message, deleted, failed),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun maybePromptRename() {
        if (renameDialogShowing) return
        val file = resolveLastOutputFile() ?: return
        if (!shouldAskRename(file)) return
        if (lastRenamePromptPath == file.absolutePath) return
        lastRenamePromptPath = file.absolutePath
        showRenameDialog(file)
    }

    private fun shouldAskRename(file: File): Boolean {
        return file.extension.equals("xml", ignoreCase = true) &&
            file.nameWithoutExtension.startsWith("sess-")
    }

    private fun showRenameDialog(file: File) {
        renameDialogShowing = true
        val input = EditText(this).apply {
            setText(file.nameWithoutExtension)
            setSelection(text.length)
            hint = getString(R.string.rename_dialog_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.rename_dialog_title))
            .setView(input)
            .setNegativeButton(getString(R.string.rename_dialog_skip)) { dialog, _ ->
                renameDialogShowing = false
                dialog.dismiss()
            }
            .setPositiveButton(getString(R.string.rename_dialog_save)) { dialog, _ ->
                val target = buildTargetFile(file, input.text?.toString().orEmpty())
                val success = file.absolutePath == target.absolutePath || file.renameTo(target)
                if (success) {
                    lastOutputPath = target.absolutePath
                    SessionRecorder.updateLastOutputPath(target.absolutePath)
                    refreshSessionList()
                    refreshOutputActions()
                } else {
                    lastRenamePromptPath = null
                    Toast.makeText(this, R.string.toast_rename_failed, Toast.LENGTH_SHORT).show()
                }
                renameDialogShowing = false
                dialog.dismiss()
            }
            .setOnCancelListener {
                renameDialogShowing = false
            }
            .show()
    }

    private fun buildTargetFile(currentFile: File, rawName: String): File {
        var base = rawName.trim()
        base = base.replace(".xml", "", ignoreCase = true)
        // Keep Chinese/Unicode letters and numbers; only replace path-unsafe chars.
        base = base.replace(Regex("\\s+"), " ").trim()
        base = base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        base = base.replace(Regex("_+"), "_")
        if (base.isBlank()) {
            base = "recording_${System.currentTimeMillis()}"
        }

        var candidate = File(currentFile.parentFile, "$base.xml")
        if (candidate.absolutePath == currentFile.absolutePath) return candidate
        var index = 1
        while (candidate.exists()) {
            candidate = File(currentFile.parentFile, "${base}_$index.xml")
            index++
        }
        return candidate
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun refreshSessionList() {
        val files = findSessionFiles()
        sessionFileAdapter.submitFiles(files)
        if (files.isEmpty()) {
            btnShareSelectedToFeishu.isEnabled = false
            btnDeleteSelectedSessions.isEnabled = false
        }
    }

    private fun findSessionFiles(): List<File> {
        val sessionsDir = File(getExternalFilesDir(null) ?: filesDir, "sessions")
        val files = sessionsDir.listFiles { f ->
            f.isFile && f.extension.equals("xml", ignoreCase = true)
        } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
    }

    private fun updateSessionStatusTitle(selectedCount: Int) {
        val total = findSessionFiles().size
        sessionsTitleText.text = getString(R.string.session_selection_status, selectedCount, total)
    }
}
