package com.dental.arapp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dental.arapp.databinding.ActivityArBinding
import com.google.ar.core.*
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.Renderable
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class ARActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArBinding
    private var arFragment: CustomArFragment? = null
    private lateinit var teethDetector: TeethDetector

    private var isScanning = false
    private var detectedTeeth = mutableListOf<Detection>()
    private var bracketNodes = mutableListOf<BracketNode>()
    private var selectedBracket: BracketNode? = null

    private var bracketRenderable: Renderable? = null
    private var isLoadingModel = false
    private var isARInitialized = false

    private var rotationAngle = 0f
    private var scaleValue = 1f

    companion object {
        private const val TAG = "ARActivity"
        private const val BRACKET_MODEL_NAME = "Bracket.obj"
        private const val DETECTION_INTERVAL = 500L
    }

    data class BracketNode(
        val transformableNode: TransformableNode,
        val anchorNode: AnchorNode,
        val id: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            binding = ActivityArBinding.inflate(layoutInflater)
            setContentView(binding.root)

            Log.d(TAG, "onCreate: Starting AR Activity")

            setupViews()
            initializeTeethDetector()

            // Delay AR setup to ensure fragment is ready
            binding.root.post {
                setupARFragment()
            }

        } catch (e: Exception) {
            Log.e(TAG, "onCreate failed: ${e.message}", e)
            showErrorAndFinish("Failed to initialize AR: ${e.message}")
        }
    }

    fun handleARException(message: String) {
        runOnUiThread {
            Log.e(TAG, "AR Exception handled: $message")
            AlertDialog.Builder(this)
                .setTitle("AR Error")
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    // Update setupARFragment method
    private fun setupARFragment() {
        try {
            // Use supportFragmentManager transaction for safer fragment handling
            val fragmentManager = supportFragmentManager
            var fragment = fragmentManager.findFragmentById(R.id.arFragment)

            if (fragment == null) {
                Log.d(TAG, "Creating new CustomArFragment")
                fragment = CustomArFragment()
                fragmentManager.beginTransaction()
                    .add(R.id.arFragment, fragment)
                    .commit()
                fragmentManager.executePendingTransactions()
            }

            arFragment = fragment as? CustomArFragment

            if (arFragment == null) {
                Log.e(TAG, "Failed to cast fragment to CustomArFragment")
                showErrorAndFinish("Failed to initialize AR fragment")
                return
            }

            Log.d(TAG, "ARFragment initialized, setting up listeners")

            // Wait for the fragment to be fully ready
            arFragment?.view?.post {
                setupARListeners()
            }

        } catch (e: Exception) {
            Log.e(TAG, "setupARFragment failed: ${e.message}", e)
            showErrorAndFinish("AR initialization failed: ${e.message}")
        }
    }

    private fun loadBracketModel() {
        if (!isARInitialized) {
            Log.w(TAG, "Cannot load model: AR not initialized yet")
            return
        }

        isLoadingModel = true
        updateStatus("Loading bracket model...")

        try {
            val assetFiles = assets.list("") ?: emptyArray()
            Log.d(TAG, "Assets found: ${assetFiles.joinToString()}")

            val hasModel = assetFiles.any { it.equals(BRACKET_MODEL_NAME, ignoreCase = true) }

            if (hasModel) {
                Log.d(TAG, "Found $BRACKET_MODEL_NAME, attempting to load...")

                ModelRenderable.builder()
                    .setSource(this, Uri.parse(BRACKET_MODEL_NAME))
                    .setIsFilamentGltf(false)  // OBJ format
                    .build()
                    .thenAccept { renderable ->
                        bracketRenderable = renderable
                        isLoadingModel = false
                        Log.d(TAG, "3D bracket model loaded successfully")
                        runOnUiThread {
                            updateStatus("Ready - Tap Start Scanning")
                            Toast.makeText(this, "✓ 3D Bracket Model Loaded", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .exceptionally { throwable ->
                        Log.e(TAG, "Failed to load OBJ model: ${throwable?.message}", throwable)
                        runOnUiThread {
                            Toast.makeText(this, "Using simple bracket (model load failed)", Toast.LENGTH_SHORT).show()
                        }
                        createFallbackBracket()
                        return@exceptionally null
                    }
            } else {
                Log.w(TAG, "No $BRACKET_MODEL_NAME found in assets, using fallback")
                createFallbackBracket()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bracket model: ${e.message}", e)
            createFallbackBracket()
        }
    }

    private fun setupARListeners() {
        try {
            // Set up session initialization listener
            arFragment?.setOnSessionInitializationListener { session ->
                Log.d(TAG, "AR Session initialized successfully")
                isARInitialized = true
                runOnUiThread {
                    updateStatus("AR Ready - Loading bracket model...")
                    loadBracketModel()
                }
            }

            // Check if session already exists (in case listener was set after initialization)
            arFragment?.arSceneView?.session?.let { session ->
                Log.d(TAG, "Session already exists in setupARListeners")
                if (!isARInitialized) {
                    isARInitialized = true
                    runOnUiThread {
                        updateStatus("AR Ready - Loading bracket model...")
                        loadBracketModel()
                    }
                }
            }

            var lastDetectionTime = 0L
            arFragment?.arSceneView?.scene?.addOnUpdateListener { frameTime ->
                try {
                    val frame = arFragment?.arSceneView?.arFrame
                    if (frame != null) {
                        val currentTime = System.currentTimeMillis()
                        if (isScanning && currentTime - lastDetectionTime > DETECTION_INTERVAL) {
                            onUpdateFrame(frame)
                            lastDetectionTime = currentTime
                        }
                        updateTrackingStatus(frame)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Frame update error: ${e.message}")
                }
            }

            arFragment?.setOnTapArPlaneListener { hitResult, plane, motionEvent ->
                if (isARInitialized && !isLoadingModel && bracketRenderable != null) {
                    onPlaneTapped(hitResult, plane)
                } else if (!isARInitialized) {
                    Toast.makeText(this, "AR is still initializing...", Toast.LENGTH_SHORT).show()
                } else if (isLoadingModel) {
                    Toast.makeText(this, "Bracket model is still loading...", Toast.LENGTH_SHORT).show()
                }
            }

            Log.d(TAG, "AR listeners setup complete")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up AR listeners: ${e.message}", e)
        }
    }



    private fun createFallbackBracket() {
        if (!isARInitialized) {
            Log.w(TAG, "Cannot create fallback: AR not initialized")
            return
        }

        try {
            MaterialFactory.makeOpaqueWithColor(this, Color(0.85f, 0.85f, 0.9f, 1.0f))
                .thenAccept { material ->
                    bracketRenderable = ShapeFactory.makeCube(
                        Vector3(0.004f, 0.004f, 0.002f),
                        Vector3.zero(),
                        material
                    )
                    isLoadingModel = false
                    Log.d(TAG, "Fallback bracket created successfully")
                    runOnUiThread {
                        updateStatus("Ready - Tap Start Scanning")
                        Toast.makeText(this, "Using simple bracket model", Toast.LENGTH_SHORT).show()
                    }
                }
                .exceptionally { throwable ->
                    Log.e(TAG, "Failed to create fallback bracket: ${throwable?.message}", throwable)
                    isLoadingModel = false
                    runOnUiThread {
                        updateStatus("Error loading bracket")
                        Toast.makeText(this, "Failed to create bracket model", Toast.LENGTH_SHORT).show()
                    }
                    return@exceptionally null
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating fallback: ${e.message}", e)
            isLoadingModel = false
        }
    }

    private fun onUpdateFrame(frame: Frame) {
        try {
            if (isScanning) {
                processFrame(frame)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in frame update: ${e.message}")
        }
    }

    private fun updateTrackingStatus(frame: Frame) {
        try {
            val trackingState = frame.camera.trackingState

            runOnUiThread {
                when (trackingState) {
                    TrackingState.TRACKING -> {
                        if (!isScanning && isARInitialized) {
                            updateStatus("Ready - Tap Start Scanning")
                        }
                    }
                    TrackingState.PAUSED -> {
                        updateStatus("Move device slowly")
                    }
                    TrackingState.STOPPED -> {
                        updateStatus("Tracking lost - Move device")
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating tracking status: ${e.message}")
        }
    }

    private fun processFrame(frame: Frame) {
        try {
            frame.acquireCameraImage().use { image ->
                val detections = teethDetector.detect(image)

                if (detections.isNotEmpty()) {
                    detectedTeeth.clear()
                    detectedTeeth.addAll(detections)

                    runOnUiThread {
                        updateDetectionProgress(detections.size)
                    }
                }
            }
        } catch (e: NotYetAvailableException) {
            // Camera image not yet available - normal, ignore
        } catch (e: Exception) {
            Log.e(TAG, "Error processing frame: ${e.message}")
        }
    }

    private fun updateDetectionProgress(teethCount: Int) {
        val progress = (teethCount * 100) / 32
        binding.detectionProgressBar.progress = progress
        binding.detectionStatusText.text = "Detected: $teethCount teeth"
    }

    private fun setupViews() {
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.infoButton.setOnClickListener {
            showInfoDialog()
        }

        binding.scanButton.setOnClickListener {
            startScanning()
        }

        binding.captureButton.setOnClickListener {
            captureScene()
        }

        binding.resetButton.setOnClickListener {
            resetScene()
        }

        binding.rotationSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    rotationAngle = progress.toFloat()
                    binding.rotationValue.text = "$progress°"
                    applyRotationToSelected()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.scaleSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    scaleValue = 0.5f + (progress / 100f) * 1.5f
                    binding.scaleValue.text = String.format("%.1fx", scaleValue)
                    applyScaleToSelected()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.deleteButton.setOnClickListener {
            deleteSelectedBracket()
        }
    }

    private fun applyRotationToSelected() {
        selectedBracket?.let { bracket ->
            val quaternion = Quaternion.axisAngle(Vector3(0f, 1f, 0f), rotationAngle)
            bracket.transformableNode.localRotation = quaternion
        }
    }

    private fun applyScaleToSelected() {
        selectedBracket?.let { bracket ->
            bracket.transformableNode.localScale = Vector3(
                scaleValue * 0.01f,
                scaleValue * 0.01f,
                scaleValue * 0.01f
            )
        }
    }

    private fun deleteSelectedBracket() {
        selectedBracket?.let { bracket ->
            bracket.transformableNode.setParent(null)
            bracket.anchorNode.anchor?.detach()
            bracketNodes.remove(bracket)
            selectedBracket = null

            binding.controlsCard.visibility = View.GONE
            Toast.makeText(this, "Bracket deleted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initializeTeethDetector() {
        try {
            teethDetector = TeethDetector(this)
            Log.d(TAG, "Teeth detector initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize teeth detector: ${e.message}")
            Toast.makeText(this, "Detection system initialized (simulation mode)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScanning() {
        if (!isARInitialized) {
            Toast.makeText(this, "AR not ready yet, please wait...", Toast.LENGTH_SHORT).show()
            return
        }

        if (isLoadingModel) {
            Toast.makeText(this, "Loading model, please wait...", Toast.LENGTH_SHORT).show()
            return
        }

        isScanning = true
        binding.scanButton.visibility = View.GONE
        binding.captureButton.visibility = View.VISIBLE
        binding.resetButton.visibility = View.VISIBLE
        binding.detectionCard.visibility = View.VISIBLE
        binding.hintCard.visibility = View.VISIBLE

        updateStatus("Scanning... Tap surfaces to place brackets")
        Toast.makeText(this, "Tap on any surface to place bracket", Toast.LENGTH_LONG).show()
    }

    private fun onPlaneTapped(hitResult: HitResult, plane: Plane) {
        val anchor = hitResult.createAnchor()
        placeBracket(anchor)
    }

    private fun placeBracket(anchor: Anchor) {
        if (bracketRenderable == null) {
            Toast.makeText(this, "Bracket model not ready", Toast.LENGTH_SHORT).show()
            return
        }

        if (arFragment == null) {
            Toast.makeText(this, "AR not initialized", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val anchorNode = AnchorNode(anchor)
            anchorNode.setParent(arFragment!!.arSceneView.scene)

            val bracketNode = TransformableNode(arFragment!!.transformationSystem)
            bracketNode.setParent(anchorNode)
            bracketNode.renderable = bracketRenderable
            bracketNode.localScale = Vector3(0.01f, 0.01f, 0.01f)

            val id = bracketNodes.size + 1
            val wrapper = BracketNode(bracketNode, anchorNode, id)
            bracketNodes.add(wrapper)

            bracketNode.setOnTapListener { _, _ ->
                selectBracket(wrapper)
                true
            }

            bracketNode.select()
            selectBracket(wrapper)

            Toast.makeText(this, "Bracket #$id placed", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "Error placing bracket: ${e.message}", e)
            Toast.makeText(this, "Failed to place bracket: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectBracket(bracket: BracketNode) {
        selectedBracket = bracket

        binding.controlsCard.visibility = View.VISIBLE
        binding.selectedBracketText.text = "Bracket #${bracket.id} selected"

        val currentScale = bracket.transformableNode.localScale.x / 0.01f

        binding.rotationSeekBar.progress = rotationAngle.toInt()
        binding.scaleSeekBar.progress = ((currentScale - 0.5f) / 1.5f * 100).toInt()
    }

    private fun captureScene() {
        try {
            val view = arFragment?.arSceneView ?: return
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)

            PixelCopy.request(
                view,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        saveBitmap(bitmap)
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "Capture failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error capturing scene: ${e.message}", e)
            Toast.makeText(this, "Failed to capture", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmap(bitmap: Bitmap) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "dental_AR_$timestamp.jpg"
            val file = File(getExternalFilesDir(null), filename)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            runOnUiThread {
                Toast.makeText(this, "Saved: $filename", Toast.LENGTH_LONG).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error saving: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetScene() {
        try {
            bracketNodes.forEach { node ->
                node.transformableNode.setParent(null)
                node.anchorNode.anchor?.detach()
            }
            val count = bracketNodes.size
            bracketNodes.clear()
            detectedTeeth.clear()
            selectedBracket = null

            isScanning = false
            binding.scanButton.visibility = View.VISIBLE
            binding.captureButton.visibility = View.GONE
            binding.resetButton.visibility = View.GONE
            binding.detectionCard.visibility = View.GONE
            binding.hintCard.visibility = View.GONE
            binding.controlsCard.visibility = View.GONE
            binding.detectionProgressBar.progress = 0
            updateStatus("Ready to start")

            Toast.makeText(this, "Reset complete - $count brackets removed", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting scene: ${e.message}", e)
        }
    }

    private fun showInfoDialog() {
        val modelStatus = when {
            isLoadingModel -> "Loading..."
            bracketRenderable != null -> "✓ Loaded"
            else -> "Fallback Mode"
        }

        val mlStatus = if (teethDetector.hasModel()) "✓ ML Active" else "Simulation"
        val arStatus = if (isARInitialized) "✓ Ready" else "Initializing..."

        AlertDialog.Builder(this)
            .setTitle("Dental AR Bracket Placer")
            .setMessage(
                """
                STATUS:
                AR System: $arStatus
                Bracket Model: $modelStatus
                Teeth Detection: $mlStatus
                Brackets Placed: ${bracketNodes.size}
                
                HOW TO USE:
                1. Wait for "Ready" status
                2. Tap Start Scanning
                3. Point at surface/dental model
                4. Tap surface to place bracket
                5. Use gestures to adjust:
                   • Pinch to zoom
                   • Two fingers to rotate
                   • Drag to move
                6. Tap bracket to select
                7. Use controls to fine-tune
                8. Capture to save
                """.trimIndent()
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            binding.statusTextView.text = message
            Log.d(TAG, "Status: $message")
        }
    }

    private fun showErrorAndFinish(message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("AR Error")
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            arFragment?.onResume()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume: ${e.message}", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            arFragment?.onPause()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onPause: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            teethDetector.close()
            arFragment = null
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}", e)
        }
    }
}
