package com.example.tvfileserver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var qrView: ImageView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private var server: FileServer? = null
    private val PORT = 8080
    private val mainScope = MainScope()

    private val STORAGE_PERMISSION_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        ipText = findViewById(R.id.ipText)
        qrView = findViewById(R.id.qrView)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        // Скрываем QR View так как не используем QR-коды
        qrView.visibility = android.view.View.GONE

        checkPermissions()

        startButton.setOnClickListener { startServer() }
        stopButton.setOnClickListener { stopServer() }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ требует MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            // Для Android 10 и ниже
            val permissions = mutableListOf<String>()

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }

            if (permissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(this,
                    permissions.toTypedArray(), STORAGE_PERMISSION_CODE)
            }
        }
    }

    private fun startServer() {
        try {
            server = FileServer(PORT)
            server?.start()

            val ip = getLocalIpAddress()
            val serverUrl = "http://$ip:$PORT"

            statusText.text = "✅ Сервер запущен"
            ipText.text = serverUrl

            // Показываем Toast с адресом вместо QR-кода
            Toast.makeText(this, "Сервер запущен по адресу: $serverUrl", Toast.LENGTH_LONG).show()

            startButton.isEnabled = false
            stopButton.isEnabled = true

        } catch (e: Exception) {
            statusText.text = "❌ Ошибка: ${e.message}"
            Toast.makeText(this, "Ошибка запуска сервера", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null

        statusText.text = "⏹️ Сервер остановлен"
        ipText.text = ""

        startButton.isEnabled = true
        stopButton.isEnabled = false

        Toast.makeText(this, "Сервер остановлен", Toast.LENGTH_SHORT).show()
    }

    private fun getLocalIpAddress(): String {
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ipInt = wifiManager.connectionInfo.ipAddress
        return Formatter.formatIpAddress(ipInt)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        mainScope.cancel()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Разрешения получены", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Нужны разрешения для работы с файлами", Toast.LENGTH_LONG).show()
            }
        }
    }
}