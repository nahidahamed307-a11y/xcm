package com.example.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.PresetData
import com.example.parser.XmlPresetParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CameraUiState(
    val presets: List<PresetData> = emptyList(),
    val selectedPreset: PresetData = PresetData.ORIGINAL,
    val presetIntensity: Float = 1.0f, // 0.0 to 1.0
    val flashMode: Int = ImageCapture.FLASH_MODE_OFF,
    val isRawModeEnabled: Boolean = false,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val lastCapturedUri: Uri? = null,
    val isCapturing: Boolean = false,
    val statusMessage: String? = null
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    init {
        loadInitialPresets()
    }

    private fun loadInitialPresets() {
        val builtIn = XmlPresetParser.getBuiltInPresets()
        _uiState.update {
            it.copy(
                presets = builtIn,
                // Default to Warm Cinematic so user immediately sees real-time Lightroom color grading
                selectedPreset = builtIn.getOrNull(1) ?: PresetData.ORIGINAL
            )
        }
    }

    fun selectPreset(preset: PresetData) {
        _uiState.update { it.copy(selectedPreset = preset) }
    }

    fun setPresetIntensity(intensity: Float) {
        _uiState.update { it.copy(presetIntensity = intensity.coerceIn(0f, 1f)) }
    }

    fun toggleRawMode() {
        _uiState.update { it.copy(isRawModeEnabled = !it.isRawModeEnabled) }
    }

    fun setFlashMode(mode: Int) {
        _uiState.update { it.copy(flashMode = mode) }
    }

    fun setLensFacing(facing: Int) {
        _uiState.update { it.copy(lensFacing = facing) }
    }

    fun onCaptureStart() {
        _uiState.update { it.copy(isCapturing = true) }
    }

    fun onCaptureSuccess(uri: Uri) {
        _uiState.update {
            it.copy(
                isCapturing = false,
                lastCapturedUri = uri,
                statusMessage = "Photo saved with ${_uiState.value.selectedPreset.name}"
            )
        }
    }

    fun onCaptureError(error: String) {
        _uiState.update {
            it.copy(
                isCapturing = false,
                statusMessage = "Capture failed: $error"
            )
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    /**
     * Imports an Adobe Lightroom .xml or .xmp file picked by the user.
     */
    fun importPresetFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val fileName = queryFileName(uri) ?: "Preset.xmp"

                val parsedPreset = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        XmlPresetParser.parseFromStream(stream, fileName)
                    }
                }

                if (parsedPreset != null) {
                    _uiState.update { state ->
                        val updatedList = state.presets.toMutableList().apply {
                            // Insert right after Original
                            add(1, parsedPreset)
                        }
                        state.copy(
                            presets = updatedList,
                            selectedPreset = parsedPreset,
                            statusMessage = "Imported '${parsedPreset.name}'"
                        )
                    }
                } else {
                    _uiState.update { it.copy(statusMessage = "Could not parse preset file") }
                }
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Failed to import preset: ${e.message}", e)
                _uiState.update { it.copy(statusMessage = "Failed to import: ${e.localizedMessage}") }
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        val cursor = getApplication<Application>().contentResolver.query(
            uri, null, null, null, null
        )
        return cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) it.getString(nameIndex) else null
            } else null
        }
    }
}
