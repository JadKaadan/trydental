package com.dental.arapp

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.sceneform.ux.ArFragment

class CustomArFragment : ArFragment() {

    companion object {
        private const val TAG = "CustomArFragment"
    }

    private var sessionInitListener: ((Session) -> Unit)? = null

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
    }

    override fun onResume() {
        super.onResume()

        // Configure the AR session when it's created
        try {
            arSceneView?.session?.let { session ->
                configureSession(session)
                sessionInitListener?.invoke(session)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring session in onResume: ${e.message}", e)
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

            Log.d(TAG, "AR Session configured successfully with advanced features")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring AR session: ${e.message}", e)

            val message = "Failed to configure AR session: ${e.message}"
            (activity as? ARActivity)?.handleARException(message)
        }
    }
}
