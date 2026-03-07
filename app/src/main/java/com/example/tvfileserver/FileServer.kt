package com.example.tvfileserver

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class FileServer(port: Int) : NanoHTTPD(port) {
    private val TAG = "FileServer"
    private val baseDir = File("/storage/emulated/0/Download")
    private val CHUNK_SIZE = 8192 // 8KB

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        // Критически важно: разрешаем переиспользование адреса
        try {
            val field = this::class.java.superclass.getDeclaredField("myServerSocket")
            field.isAccessible = true
            val serverSocket = field.get(this) as? java.net.ServerSocket
            serverSocket?.setReuseAddress(true)
        } catch (e: Exception) {
            Log.e(TAG, "Could not set reuse address", e)
        }
    }

    override fun start() {
        try {
            // Останавливаем, если уже запущен
            stop()
            super.start()
        } catch (e: IOException) {
            Log.e(TAG, "Error starting server", e)
            // Пробуем еще раз после паузы
            try {
                Thread.sleep(2000)
                super.start()
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start server after retry", e2)
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        // Очищаем память перед каждым запросом
        Runtime.getRuntime().gc()

        return try {
            when (session.method) {
                Method.GET -> handleGet(session)
                Method.POST -> handlePost(session)
                else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error serving request", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server error")
        }
    }

    private fun handleGet(session: IHTTPSession): Response {
        val uri = session.uri
        return when {
            uri == "/" || uri.isEmpty() -> serveFileList()
            uri.startsWith("/download/") -> serveFileDownload(uri)
            uri.startsWith("/delete/") -> handleDelete(uri)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()

            // Устанавливаем временную директорию
            System.setProperty("java.io.tmpdir", baseDir.absolutePath)

            // Парсим с ограниченным буфером
            session.parseBody(files)

            val tempFile = files["postData"]
            if (tempFile != null) {
                val temp = File(tempFile)
                val fileName = session.parameters["filename"]?.firstOrNull()
                    ?: "uploaded_${System.currentTimeMillis()}"

                val targetFile = File(baseDir, fileName)

                // Копируем маленькими кусками
                copyFileWithBuffer(temp, targetFile)

                // Удаляем временный файл
                temp.delete()

                Log.d(TAG, "File uploaded: $fileName, size: ${targetFile.length()}")

                // Возвращаем минимальный ответ
                newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
            } else {
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No file")
            }
        } catch (e: NanoHTTPD.ResponseException) {
            Log.e(TAG, "Upload error", e)
            newFixedLengthResponse(e.status, "text/plain", "Upload failed")
        } catch (e: Exception) {
            Log.e(TAG, "Upload error", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error")
        } finally {
            // Принудительная очистка памяти
            Runtime.getRuntime().gc()
        }
    }

    private fun copyFileWithBuffer(source: File, destination: File) {
        val buffer = ByteArray(CHUNK_SIZE)
        FileInputStream(source).use { input ->
            FileOutputStream(destination).use { output ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    output.flush()
                }
            }
        }
    }

    private fun serveFileList(): Response {
        try {
            val files = baseDir.listFiles()?.filter { it.isFile } ?: emptyList()

            // Минимальный HTML ответ
            val html = buildString {
                append("<!DOCTYPE html><html><head><title>TV File Server</title>")
                append("<meta charset='UTF-8'>")
                append("<style>")
                append("body{font-family:Arial;margin:20px;background:#f5f5f5}")
                append(".container{max-width:800px;margin:auto;background:white;padding:20px;border-radius:8px}")
                append("table{width:100%;border-collapse:collapse}")
                append("th{background:#2196F3;color:white;padding:10px;text-align:left}")
                append("td{padding:10px;border-bottom:1px solid #ddd}")
                append("</style>")
                append("</head><body><div class='container'>")
                append("<h1>📺 TV File Server</h1>")

                // Форма загрузки
                append("<form method='POST' enctype='multipart/form-data' style='margin:20px 0;padding:20px;border:2px dashed #2196F3;border-radius:8px'>")
                append("<h3>📤 Загрузить файл</h3>")
                append("<input type='file' name='postData' id='fileInput' required style='margin:10px'>")
                append("<input type='hidden' name='filename' id='filename'>")
                append("<br><button type='submit' style='background:#2196F3;color:white;padding:10px 20px;border:none;border-radius:4px;cursor:pointer'>Загрузить</button>")
                append("</form>")

                // Список файлов
                append("<h3>📁 Файлы (${files.size})</h3>")
                if (files.isEmpty()) {
                    append("<p>Нет файлов</p>")
                } else {
                    append("<table><tr><th>Имя</th><th>Размер</th><th>Действия</th></tr>")
                    files.forEach { file ->
                        append("<tr>")
                        append("<td>${file.name}</td>")
                        append("<td>${formatFileSize(file.length())}</td>")
                        append("<td><a href='/download/${file.name}'>📥</a> | <a href='/delete/${file.name}' onclick='return confirm(\"Удалить?\")'>🗑️</a></td>")
                        append("</tr>")
                    }
                    append("</table>")
                }

                append("<script>document.getElementById('fileInput')?.addEventListener('change',function(e){")
                append("if(e.target.files.length>0){")
                append("document.getElementById('filename').value=e.target.files[0].name;")
                append("}})</script>")
                append("</div></body></html>")
            }

            val response = newFixedLengthResponse(html)
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            return response

        } catch (e: Exception) {
            Log.e(TAG, "Error serving file list", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error loading files")
        }
    }

    private fun serveFileDownload(uri: String): Response {
        val fileName = uri.substringAfter("/download/")
        val file = File(baseDir, fileName)

        return if (file.exists() && file.isFile) {
            try {
                val mimeType = when {
                    fileName.endsWith(".apk") -> "application/vnd.android.package-archive"
                    fileName.endsWith(".mp4") || fileName.endsWith(".avi") || fileName.endsWith(".mkv") -> "video/mp4"
                    fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".png") -> "image/jpeg"
                    fileName.endsWith(".mp3") || fileName.endsWith(".wav") -> "audio/mpeg"
                    else -> "application/octet-stream"
                }

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    FileInputStream(file),
                    file.length()
                )
                response.addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
                response.addHeader("Cache-Control", "no-cache")
                response

            } catch (e: Exception) {
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error reading file")
            }
        } else {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }
    }

    private fun handleDelete(uri: String): Response {
        val fileName = uri.substringAfter("/delete/")
        val file = File(baseDir, fileName)

        return if (file.exists() && file.delete()) {
            // Перенаправляем обратно на главную
            val html = "<html><head><meta http-equiv='refresh' content='1;url=/'></head><body>✅ Файл удален</body></html>"
            newFixedLengthResponse(Response.Status.OK, "text/html", html)
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Delete failed")
        }
    }

    private fun formatFileSize(size: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var fileSize = size.toDouble()
        var unitIndex = 0

        while (fileSize > 1024 && unitIndex < units.size - 1) {
            fileSize /= 1024
            unitIndex++
        }

        return "%.2f %s".format(fileSize, units[unitIndex])
    }

    override fun stop() {
        super.stop()
        // Очищаем память при остановке
        Runtime.getRuntime().gc()
    }
}