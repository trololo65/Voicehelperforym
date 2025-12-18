package com.example.voicehelperforym

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.offlinespeech.VoiceService

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "Разрешение на аудио получено")
                startVoiceService()
            } else {
                Log.e("MainActivity", "Разрешение на аудио не предоставлено")
                Toast.makeText(this, "Нужно разрешение на запись аудио", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d("MainActivity", "Приложение запущено")

        btnStart = findViewById(R.id.btnStartService)
        btnStop = findViewById(R.id.btnStopService)

        btnStart.setOnClickListener {
            Log.d("MainActivity", "Кнопка запуска нажата")
            checkAudioPermissionAndStart()
        }

        btnStop.setOnClickListener {
            Log.d("MainActivity", "Кнопка остановки нажата")
            stopVoiceService()
        }

        // Для Android 12+ запускаем при открытии приложения
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkAudioPermissionAndStart()
        }
    }

    private fun stopVoiceService() {
        val intent = Intent(this, VoiceService::class.java)
        stopService(intent)
        Toast.makeText(this, "Сервис остановлен", Toast.LENGTH_SHORT).show()
    }

    private fun checkAudioPermissionAndStart() {
        Log.d("MainActivity", "Проверка разрешений...")

        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED -> {
                Log.d("MainActivity", "Разрешение уже есть")
                startVoiceService()
            }

            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                Toast.makeText(this, "Для распознавания голоса нужно разрешение на запись аудио", Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            else -> {
                Log.d("MainActivity", "Запрашиваем разрешение")
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startVoiceService() {
        Log.d("MainActivity", "Запуск VoiceService...")

        try {
            val intent = Intent(this, VoiceService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Для Android 8.0+ используем startForegroundService
                Log.d("MainActivity", "Запуск через startForegroundService")
                startForegroundService(intent)
            } else {
                Log.d("MainActivity", "Запуск через startService")
                startService(intent)
            }

        } catch (e: Exception) {
            Log.e("MainActivity", "Ошибка при запуске сервиса", e)

            // Если ошибка связана с ограничениями Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is IllegalStateException) {
                Toast.makeText(this,
                    "Для Android 12+ нужна кнопка для запуска. Нажмите 'Запустить'",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    // Можно добавить кнопку в layout для явного запуска
    // или использовать метод onResume
    override fun onResume() {
        super.onResume()
        // Для Android 12+ можно попробовать запустить здесь
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkAudioPermissionAndStart()
        }
    }
}