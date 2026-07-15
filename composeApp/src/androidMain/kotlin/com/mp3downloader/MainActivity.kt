package com.mp3downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mp3downloader.data.storage.loadAppearance
import com.mp3downloader.data.storage.saveAppearance
import com.mp3downloader.ui.MainViewModel
import com.mp3downloader.ui.theme.ThemeManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var serviceStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions()

        val saved = loadAppearance()
        ThemeManager.loadFrom(saved)

        setContent { App() }

        // Observar el conteo de descargas activas para gestionar el
        // Foreground Service. Cuando hay al menos una descarga, iniciamos
        // el servicio para mantener la CPU activa y evitar que Android
        // suspenda la conexión. Cuando todas terminan, lo detenemos.
        observeDownloadService()
    }

    override fun onPause() {
        super.onPause()
        saveAppearance(ThemeManager.settings.value)
    }

    override fun onStop() {
        super.onStop()
        saveAppearance(ThemeManager.settings.value)
    }

    private fun observeDownloadService() {
        lifecycleScope.launch {
            try {
                // Obtenemos el ViewModel desde Koin para observar el conteo
                val viewModel = get<MainViewModel>(MainViewModel::class.java)
                viewModel.foregroundDownloadsCount.collectLatest { count ->
                    Log.d(TAG, "Descargas activas: $count")
                    if (count > 0 && !serviceStarted) {
                        startDownloadService()
                    } else if (count <= 0 && serviceStarted) {
                        stopDownloadService()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al observar descargas: ${e.message}")
            }
        }
    }

    private fun startDownloadService() {
        try {
            val intent = Intent(this, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            serviceStarted = true
            Log.d(TAG, "Foreground Service iniciado")
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar servicio: ${e.message}")
        }
    }

    private fun stopDownloadService() {
        try {
            val intent = Intent(this, DownloadService::class.java)
            stopService(intent)
            serviceStarted = false
            Log.d(TAG, "Foreground Service detenido")
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener servicio: ${e.message}")
        }
    }

    private fun requestPermissions() {
        val needed = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }
}
