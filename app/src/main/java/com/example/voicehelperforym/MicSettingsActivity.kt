package com.example.voicehelperforym

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.offlinespeech.VoiceSettings
import com.example.offlinespeech.VoskRecognizer

class MicSettingsActivity : AppCompatActivity() {

    private lateinit var tvMicRangeValue: TextView
    private lateinit var seekMicRange: SeekBar
    private lateinit var switchNoiseSuppression: Switch
    private lateinit var radioGroupAudioSource: RadioGroup
    private lateinit var radioVoiceRecognition: RadioButton
    private lateinit var radioVoiceCommunication: RadioButton
    private lateinit var tvMinPartialValue: TextView
    private lateinit var seekMinPartial: SeekBar
    private lateinit var btnBackToMain: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mic_settings)

        tvMicRangeValue = findViewById(R.id.tvMicRangeValue)
        seekMicRange = findViewById(R.id.seekMicRange)
        switchNoiseSuppression = findViewById(R.id.switchNoiseSuppression)
        radioGroupAudioSource = findViewById(R.id.radioGroupAudioSource)
        radioVoiceRecognition = findViewById(R.id.radioVoiceRecognition)
        radioVoiceCommunication = findViewById(R.id.radioVoiceCommunication)
        tvMinPartialValue = findViewById(R.id.tvMinPartialValue)
        seekMinPartial = findViewById(R.id.seekMinPartial)
        btnBackToMain = findViewById(R.id.btnBackToMain)

        // Инициализируем контролы текущими настройками
        initMicRange()
        initNoiseSuppression()
        initAudioSourceMode()
        initMinPartialLength()

        btnBackToMain.setOnClickListener {
            // Просто закрываем активити и возвращаемся на главный экран
            finish()
        }
    }

    private fun initMicRange() {
        val current = VoiceSettings.getMicRangeLevel(this)
        seekMicRange.max = 10
        seekMicRange.progress = current
        updateMicRangeText(current)

        seekMicRange.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                VoiceSettings.setMicRangeLevel(this@MicSettingsActivity, progress)
                updateMicRangeText(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateMicRangeText(level: Int) {
        val description = when {
            level <= 3 -> "близко"
            level <= 7 -> "средне"
            else -> "далеко"
        }
        tvMicRangeValue.text = "Дальность: $level/10 ($description)"
    }

    private fun initNoiseSuppression() {
        val enabled = VoiceSettings.isNoiseSuppressionEnabled(this)
        switchNoiseSuppression.isChecked = enabled

        switchNoiseSuppression.setOnCheckedChangeListener { _, isChecked ->
            VoiceSettings.setNoiseSuppressionEnabled(this, isChecked)
        }
    }

    private fun initAudioSourceMode() {
        val mode = VoiceSettings.getAudioSourceMode(this)
        when (mode) {
            VoskRecognizer.AUDIO_SOURCE_MODE_VOICE_COMMUNICATION -> {
                radioVoiceCommunication.isChecked = true
            }
            else -> {
                radioVoiceRecognition.isChecked = true
            }
        }

        radioGroupAudioSource.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.radioVoiceCommunication -> VoskRecognizer.AUDIO_SOURCE_MODE_VOICE_COMMUNICATION
                else -> VoskRecognizer.AUDIO_SOURCE_MODE_VOICE_RECOGNITION
            }
            VoiceSettings.setAudioSourceMode(this, newMode)
        }
    }

    private fun initMinPartialLength() {
        val current = VoiceSettings.getMinPartialLength(this)
        // Диапазон 1–6, SeekBar работает от 0, так что progress = value-1
        seekMinPartial.max = 5
        seekMinPartial.progress = current - 1
        updateMinPartialText(current)

        seekMinPartial.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = (progress + 1).coerceIn(1, 6)
                VoiceSettings.setMinPartialLength(this@MicSettingsActivity, value)
                updateMinPartialText(value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateMinPartialText(length: Int) {
        tvMinPartialValue.text = "Минимальная длина partial: $length символов"
    }
}


