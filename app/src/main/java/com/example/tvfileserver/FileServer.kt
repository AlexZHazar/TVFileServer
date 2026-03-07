package com.example.tvfileserver

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File
import java.io.FileInputStream
import java.io.IOException

class FileServer(port: Int) : NanoHTTPD(port) {
    private val TAG = "FileServer"
    private val baseDir = File("/storage/emulated/0/Download")
    private val MAX_FILE_SIZE = 1024L * 1024L * 1024L // 1GB лимит

    init {
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.method) {
            Method.GET -> handleGet(session)
            Method.POST -> handlePost(session)
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Method not allowed")
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
            // Устанавливаем временную директорию для больших файлов
            System.setProperty("java.io.tmpdir", baseDir.absolutePath)

            val files = HashMap<String, String>()

            // Парсим multipart данные с увеличенным лимитом
            session.parseBody(files)

            val tempFile = files["postData"]
            if (tempFile != null) {
                val temp = File(tempFile)

                // Проверяем размер файла
                if (temp.length() > MAX_FILE_SIZE) {
                    temp.delete()
                    return newFixedLengthResponse(
                        Response.Status.PAYLOAD_TOO_LARGE,
                        "text/plain",
                        "File too large. Maximum size is 1GB"
                    )
                }

                // Получаем имя файла из параметров
                val fileName = session.parameters["filename"]?.firstOrNull()
                    ?: "uploaded_${System.currentTimeMillis()}"

                val targetFile = File(baseDir, fileName)

                // Копируем файл с буферизацией
                temp.copyTo(targetFile, overwrite = true)
                temp.delete() // Удаляем временный файл

                Log.d(TAG, "File uploaded: $fileName, size: ${targetFile.length()}")

                newFixedLengthResponse(
                    Response.Status.OK,
                    "text/plain",
                    "File uploaded successfully: $fileName"
                )
            } else {
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "text/plain",
                    "No file uploaded"
                )
            }
        } catch (e: NanoHTTPD.ResponseException) {
            Log.e(TAG, "Upload error: ${e.message}")
            newFixedLengthResponse(
                e.status,
                "text/plain",
                "Upload failed: ${e.message}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Upload error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Upload failed: ${e.message}"
            )
        }
    }

    private fun serveFileList(): Response {
        val files = baseDir.listFiles()?.map { file ->
            """
            <tr>
                <td>${file.name}</td>
                <td>${formatFileSize(file.length())}</td>
                <td>
                    <a href="/download/${file.name}">📥 Скачать</a> | 
                    <a href="/delete/${file.name}" onclick="return confirm('Удалить?')">🗑️ Удалить</a>
                </td>
            </tr>
            """.trimIndent()
        }?.joinToString("") ?: ""

        val html = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>TV File Server</title>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body { font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }
                .container { max-width: 800px; margin: auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                h1 { color: #2196F3; }
                table { width: 100%%; border-collapse: collapse; margin-top: 20px; }
                th { background: #2196F3; color: white; padding: 10px; text-align: left; }
                td { padding: 10px; border-bottom: 1px solid #ddd; }
                tr:hover { background: #f5f5f5; }
                .upload-form { border: 2px dashed #2196F3; padding: 20px; text-align: center; margin: 20px 0; border-radius: 8px; }
                .btn { background: #2196F3; color: white; padding: 10px 20px; border: none; border-radius: 4px; cursor: pointer; font-size: 16px; }
                .btn:hover { background: #1976D2; }
                .file-info { color: #666; margin-top: 10px; }
                .warning { color: #f44336; font-size: 14px; margin-top: 5px; }
                a { text-decoration: none; color: #2196F3; }
                a:hover { text-decoration: underline; }
            </style>
        </head>
        <body>
            <div class="container">
                <h1>📺 TV File Server</h1>
                <p class="file-info">Папка загрузок: ${baseDir.absolutePath}</p>
                <p class="warning">Максимальный размер файла: 1GB</p>
                
                <div class="upload-form">
                    <h3>📤 Загрузить файл</h3>
                    <form method="POST" enctype="multipart/form-data">
                        <input type="file" name="postData" id="fileInput" required style="margin: 10px;">
                        <input type="hidden" name="filename" id="filename">
                        <br>
                        <button type="submit" class="btn">Загрузить</button>
                    </form>
                </div>
                
                <h3>📁 Файлы на устройстве</h3>
                <table>
                    <tr>
                        <th>Имя файла</th>
                        <th>Размер</th>
                        <th>Действия</th>
                    </tr>
                    $files
                </table>
            </div>
            
            <script>
                document.getElementById('fileInput').addEventListener('change', function(e) {
                    if (e.target.files.length > 0) {
                        document.getElementById('filename').value = e.target.files[0].name;
                        
                        // Проверка размера файла на клиенте
                        var fileSize = e.target.files[0].size;
                        var maxSize = 1024 * 1024 * 1024; // 1GB
                        if (fileSize > maxSize) {
                            alert('Файл слишком большой! Максимальный размер: 1GB');
                            e.target.value = '';
                        }
                    }
                });
            </script>
        </body>
        </html>
        """.trimIndent()

        return newFixedLengthResponse(html)
    }

    private fun serveFileDownload(uri: String): Response {
        val fileName = uri.substringAfter("/download/")
        val file = File(baseDir, fileName)

        return if (file.exists() && file.isFile) {
            try {
                val mimeType = when {
                    fileName.endsWith(".mp4") || fileName.endsWith(".avi") || fileName.endsWith(".mkv") -> "video/mp4"
                    fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") || fileName.endsWith(".png") -> "image/jpeg"
                    fileName.endsWith(".mp3") || fileName.endsWith(".wav") -> "audio/mpeg"
                    fileName.endsWith(".txt") || fileName.endsWith(".log") -> "text/plain"
                    fileName.endsWith(".pdf") -> "application/pdf"
                    fileName.endsWith(".apk") -> "application/vnd.android.package-archive"
                    else -> "application/octet-stream"
                }

                val response = newFixedLengthResponse(
                    Response.Status.OK,
                    mimeType,
                    FileInputStream(file),
                    file.length()
                )
                response.addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
                response

            } catch (e: Exception) {
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "text/plain",
                    "Error reading file: ${e.message}"
                )
            }
        } else {
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "File not found"
            )
        }
    }

    private fun handleDelete(uri: String): Response {
        val fileName = uri.substringAfter("/delete/")
        val file = File(baseDir, fileName)

        return if (file.exists() && file.delete()) {
            val html = """
            <html>
            <head>
                <meta http-equiv="refresh" content="2;url=/">
            </head>
            <body>
                <h2>✅ Файл удален</h2>
                <p>Перенаправление через 2 секунды...</p>
                <p><a href="/">Вернуться назад</a></p>
            </body>
            </html>
            """.trimIndent()
            newFixedLengthResponse(Response.Status.OK, "text/html", html)
        } else {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Delete failed"
            )
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
}