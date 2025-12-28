package com.example.voicehelperforym

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.offlinespeech.VoiceService
import com.example.offlinespeech.VoskRecognizer

class MainActivity : AppCompatActivity(), VoskRecognizer.RecognitionListener {

    private lateinit var voskRecognizer: VoskRecognizer
    private var isRecognizing = false

    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnToggleRecognition: Button
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            voskRecognizer.initialize()
        } else {
            Toast.makeText(this, "Разрешение на запись аудио не предоставлено", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        btnToggleRecognition = findViewById(R.id.btnToggleRecognition)
        btnStartService = findViewById(R.id.btnStartService)
        btnStopService = findViewById(R.id.btnStopService)

        btnToggleRecognition.isEnabled = false // Disable until recognizer is ready

        voskRecognizer = VoskRecognizer(this, this)

        btnToggleRecognition.setOnClickListener {
            toggleRecognition()
        }

        btnStartService.setOnClickListener {
            startVoiceService()
        }

        btnStopService.setOnClickListener {
            stopVoiceService()
        }

        checkAudioPermission()
    }

    private fun checkAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                voskRecognizer.initialize()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun toggleRecognition() {
        if (isRecognizing) {
            voskRecognizer.stopListening()
        } else {
            voskRecognizer.startListening()
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Фоновый сервис запущен", Toast.LENGTH_SHORT).show()
    }

    private fun stopVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        stopService(intent)
        Toast.makeText(this, "Фоновый сервис остановлен", Toast.LENGTH_SHORT).show()
    }

    private fun updateButtonState() {
        runOnUiThread {
            btnToggleRecognition.text = if (isRecognizing) "Остановить" else "Начать распознавание"
        }
    }

    override fun onStatusChange(status: String) {
        runOnUiThread {
            tvStatus.text = status
            when (status) {
                "Готов к работе" -> {
                    btnToggleRecognition.isEnabled = true
                    btnStartService.isEnabled = true
                }
                "Слушаю..." -> {
                    isRecognizing = true
                    updateButtonState()
                    btnStartService.isEnabled = false // Disable service btn while interactive mode is on
                }
                "Остановлено" -> {
                    isRecognizing = false
                    updateButtonState()
                    btnStartService.isEnabled = true
                }
            }
        }
    }

    override fun onResult(result: String) {
        runOnUiThread {
            if (result.isEmpty()) {
                tvResult.text = "Скажите что-нибудь..."
            } else {
                tvResult.text = result
            }
        }
    }

    override fun onPartialResult(partialResult: String) {
        runOnUiThread {
            tvResult.text = partialResult
        }
    }

    override fun onError(error: String) {
        runOnUiThread {
            tvStatus.text = "Ошибка: $error"
            isRecognizing = false
            updateButtonState()
            btnStartService.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voskRecognizer.destroy()
    }
}