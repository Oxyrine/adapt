package com.popgamma.tutor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private var albertVoice: AlbertVoice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        albertVoice = AlbertVoice(this)

        var micGranted by mutableStateOf(
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
        val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micGranted = granted
        }

        setContent {
            val viewModel: TutorViewModel = viewModel(factory = TutorViewModelFactory(BuildConfig.GEMINI_API_KEY))
            viewModel.voiceEngine = albertVoice
            TutorApp(
                viewModel = viewModel,
                micGranted = micGranted,
                onRequestMicPermission = { requestPermission.launch(Manifest.permission.RECORD_AUDIO) }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        albertVoice?.shutdown()
        albertVoice = null
    }
}
