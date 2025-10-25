package com.dental.arapp

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableException
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

        // Disable the plane discovery controller to prevent default behavior
        planeDiscoveryController.hide()
        planeDiscoveryController.setInstructionView(null)

        // Set up ArSceneView
        arSceneView.planeRenderer.isVisible = true
        arSceneView.planeRenderer.isShadowReceiver = false
    }

    fun setOnSessionInitializationListener(listener: (Session) -> Unit) {
        sessionInitListener = listener
    }

    override fun getSessionConfiguration(session: Session): Config {
        val config = super.getSessionConfiguration(session)

        try {
            // Configure ARCore session with advanced features
            config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.focusMode = Config.FocusMode.AUTO
            config.depthMode = Config.DepthMode.AUTOMATIC

            Log.d(TAG, "AR Session configured successfully with advanced features")

            // Notify listener after successful configuration
            sessionInitListener?.invoke(session)

        } catch (e: Exception) {
            Log.e(TAG, "Error configuring AR session: ${e.message}", e)
        }

        return config
    }

    override fun getSessionFeatures(): Set<Session.Feature> {
        return setOf(Session.Feature.FRONT_CAMERA)
    }

    override fun onException(exception: UnavailableException?) {
        super.onException(exception)
        Log.e(TAG, "AR Exception: ${exception?.message}", exception)

        val message = when (exception) {
            is com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException ->
                "ARCore is not installed"
            is com.google.ar.core.exceptions.UnavailableApkTooOldException ->
                "ARCore APK is too old"
            is com.google.ar.core.exceptions.UnavailableSdkTooOldException ->
                "ARCore SDK is too old"
            is com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException ->
                "This device does not support AR"
            else -> "AR is not available: ${exception?.message}"
        }

        (activity as? ARActivity)?.handleARException(message)
    }
}
