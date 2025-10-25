package com.dental.arapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dental.arapp.databinding.ActivityMainBinding
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val CAMERA_PERMISSION_CODE = 100
    private var installRequested = false

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
    }

    override fun onResume() {
        super.onResume()
        checkARCoreInstallation()
    }

    private fun setupViews() {
        binding.startButton.setOnClickListener {
            if (checkCameraPermission()) {
                checkAndStartARSession()
            } else {
                requestCameraPermission()
            }
        }
    }

    private fun checkARCoreInstallation() {
        try {
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            when (availability) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
                    Log.d(TAG, "ARCore is installed and supported")
                    binding.startButton.isEnabled = true
                }
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
                ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
                    Log.d(TAG, "ARCore is supported but not installed/updated")
                    binding.startButton.isEnabled = true
                }
                ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
                    Log.e(TAG, "Device does not support ARCore")
                    binding.startButton.isEnabled = false
                    Toast.makeText(
                        this,
                        "This device does not support AR",
                        Toast.LENGTH_LONG
                    ).show()
                }
                ArCoreApk.Availability.UNKNOWN_CHECKING,
                ArCoreApk.Availability.UNKNOWN_ERROR,
                ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> {
                    // Continue checking
                    binding.root.postDelayed({ checkARCoreInstallation() }, 200)
                }
                else -> {
                    Log.w(TAG, "Unknown ARCore availability: $availability")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking ARCore availability", e)
            Toast.makeText(this, "Error checking AR support", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndStartARSession() {
        try {
            // Request ARCore installation if needed
            val installStatus = ArCoreApk.getInstance().requestInstall(this, !installRequested)

            when (installStatus) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    Log.d(TAG, "ARCore installation requested")
                    installRequested = true
                    // The activity will be paused and resumed when installation is complete
                }
                ArCoreApk.InstallStatus.INSTALLED -> {
                    Log.d(TAG, "ARCore is installed, starting AR session")
                    startARSession()
                }
                else -> {
                    Log.e(TAG, "Unexpected install status: $installStatus")
                }
            }
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore not available", e)
            Toast.makeText(
                this,
                "ARCore is not available on this device",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AR session", e)
            Toast.makeText(
                this,
                "Error starting AR: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkAndStartARSession()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.camera_permission_required),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startARSession() {
        try {
            val intent = Intent(this, ARActivity::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error launching AR Activity", e)
            Toast.makeText(this, "Failed to start AR", Toast.LENGTH_SHORT).show()
        }
    }
}
