package com.example.offlinespeech

import android.content.Context
import android.util.Log
import java.io.*

class ModelCopier(private val context: Context) {

    fun copyModelFromAssets() {
        try {
            Log.d("VOICE", "Копирование модели из assets")

            val modelDir = File(context.filesDir, "model")

            // Очищаем старую папку
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            modelDir.mkdirs()

            Log.d("VOICE", "Папка создана: ${modelDir.absolutePath}")

            // Проверяем, что в assets есть папка 'model'
            try {
                val filesInModel = context.assets.list("model")
                if (filesInModel != null && filesInModel.isNotEmpty()) {
                    Log.d("VOICE", "Найдена папка 'model' в assets с файлами: ${filesInModel.size}")

                    // Копируем всю папку рекурсивно
                    copyAssetsFolder("model", modelDir)

                } else {
                    Log.e("VOICE", "Папка 'model' пуста или не найдена в assets")

                    // Проверяем другие возможные расположения
                    val allAssets = context.assets.list("")
                    Log.d("VOICE", "Все файлы в assets: ${allAssets?.joinToString(", ")}")

                    // Ищем файлы модели в корне assets
                    allAssets?.forEach { fileName ->
                        if (fileName.contains("am") ||
                            fileName.contains("conf") ||
                            fileName.contains("graph") ||
                            fileName.contains("ivector") ||
                            fileName.endsWith(".model") ||
                            fileName.endsWith(".zip")) {

                            Log.d("VOICE", "Копируем файл модели: $fileName")
                            copySingleFileFromAssets(fileName, File(modelDir, fileName))
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("VOICE", "Ошибка чтения assets", e)
            }

            // Проверяем результат
            val copiedFiles = modelDir.listFiles()
            if (copiedFiles != null && copiedFiles.isNotEmpty()) {
                Log.d("VOICE", "Модель скопирована успешно. Файлы: ${copiedFiles.joinToString { it.name }}")
            } else {
                Log.e("VOICE", "Модель НЕ скопирована! Папка пуста.")
                // Создаем тестовые файлы для отладки
                File(modelDir, "test.txt").writeText("Test file")
            }

        } catch (e: Exception) {
            Log.e("VOICE", "Ошибка копирования модели", e)
        }
    }

    private fun copyAssetsFolder(assetPath: String, targetDir: File) {
        try {
            val files = context.assets.list(assetPath)
            if (files == null) {
                Log.w("VOICE", "Нет файлов в папке: $assetPath")
                return
            }

            Log.d("VOICE", "Копируем папку '$assetPath' (${files.size} файлов)")

            for (fileName in files) {
                val assetFilePath = "$assetPath/$fileName"
                val targetFile = File(targetDir, fileName)

                try {
                    // Пытаемся открыть как файл
                    context.assets.open(assetFilePath).use {
                        // Если открылось, значит это файл
                        copySingleFileFromAssets(assetFilePath, targetFile)
                    }
                } catch (e: FileNotFoundException) {
                    // Если не открывается как файл, возможно это папка
                    Log.d("VOICE", "$assetFilePath - возможно папка, создаем подпапку")
                    targetFile.mkdirs()
                    copyAssetsFolder(assetFilePath, targetFile)
                } catch (e: Exception) {
                    Log.e("VOICE", "Ошибка обработки: $assetFilePath", e)
                }
            }

        } catch (e: Exception) {
            Log.e("VOICE", "Ошибка копирования папки: $assetPath", e)
        }
    }

    private fun copySingleFileFromAssets(assetPath: String, targetFile: File) {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            inputStream = context.assets.open(assetPath)
            outputStream = FileOutputStream(targetFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytes = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytes += bytesRead
            }

            outputStream.flush()
            Log.d("VOICE", "Скопирован файл: $assetPath -> ${targetFile.name} ($totalBytes байт)")

        } catch (e: FileNotFoundException) {
            Log.e("VOICE", "Файл не найден в assets: $assetPath", e)
        } catch (e: IOException) {
            Log.e("VOICE", "Ошибка при копировании файла: $assetPath", e)
        } finally {
            try {
                inputStream?.close()
                outputStream?.close()
            } catch (e: IOException) {
                Log.e("VOICE", "Ошибка при закрытии потоков", e)
            }
        }
    }
}