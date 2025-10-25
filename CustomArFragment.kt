package com.dental.arapp

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.sceneform.ux.ArFragment

class CustomArFragment : ArFragment() {

    companion object {
        private const val TAG = "CustomArFragment"
        private const val SESSION_CHECK_DELAY = 100L
        private const val MAX_RETRIES = 50 // 5 seconds max
    }

    private var sessionInitListener: ((Session) -> Unit)? = null
    private var sessionCheckCount = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onAttach(context: Context) {
        super.onAttach(context)
        Log.d(TAG, "CustomArFragment attached to context")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "CustomArFragment view created")

        // Configure ArSceneView
        arSceneView?.apply {
            planeRenderer.isVisible = true
            planeRenderer.isShadowReceiver = false
        }
    }

    fun setOnSessionInitializationListener(listener: (Session) -> Unit) {
        sessionInitListener = listener

        // If session already exists, call listener immediately
        arSceneView?.session?.let { session ->
            Log.d(TAG, "Session already exists, configuring now")
            configureSession(session)
            listener.invoke(session)
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called")

        // Start checking for session initialization
        sessionCheckCount = 0
        checkAndConfigureSession()
    }

    private fun checkAndConfigureSession() {
        val session = arSceneView?.session

        if (session != null) {
            Log.d(TAG, "Session found! Configuring...")
            try {
                configureSession(session)
                sessionInitListener?.invoke(session)
                Log.d(TAG, "Session configured and listener notified")
            } catch (e: Exception) {
                Log.e(TAG, "Error in session configuration: ${e.message}", e)

                // Retry after a delay
                if (sessionCheckCount < MAX_RETRIES) {
                    sessionCheckCount++
                    handler.postDelayed({ checkAndConfigureSession() }, SESSION_CHECK_DELAY)
                } else {
                    val message = "Failed to configure AR session after multiple retries: ${e.message}"
                    (activity as? ARActivity)?.handleARException(message)
                }
            }
        } else {
            // Session not ready yet, check again
            if (sessionCheckCount < MAX_RETRIES) {
                sessionCheckCount++
                Log.d(TAG, "Session not ready, retry $sessionCheckCount/$MAX_RETRIES")
                handler.postDelayed({ checkAndConfigureSession() }, SESSION_CHECK_DELAY)
            } else {
                Log.e(TAG, "Session failed to initialize after $MAX_RETRIES attempts")
                val message = "AR Session failed to initialize. Please restart the app."
                (activity as? ARActivity)?.handleARException(message)
            }
        }
    }

    private fun configureSession(session: Session) {
        try {
            val config = session.config

            // Configure ARCore session with advanced features
            config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.focusMode = Config.FocusMode.AUTO

            // Apply configuration
            session.configure(config)

            Log.d(TAG, "AR Session configured successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring AR session: ${e.message}", e)
            throw e // Re-throw to be caught by caller
        }
    }

    override fun onPause() {
        super.onPause()
        // Cancel any pending session checks
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        sessionInitListener = null
    }
}
