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
import com.google.ar.sceneform.AnchorNode
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
    private lateinit var arFragment: CustomArFragment
    private lateinit var teethDetector: TeethDetector

    private var isScanning = false
    private var detectedTeeth = mutableListOf<Detection>()
    private var bracketNodes = mutableListOf<BracketNode>()
    private var selectedBracket: BracketNode? = null

    private var bracketRenderable: Renderable? = null
    private var isLoadingModel = false

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
        binding = ActivityArBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupARFragment()
        setupViews()
        initializeTeethDetector()
        loadBracketModel()
    }

    private fun setupARFragment() {
        arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as CustomArFragment

        var lastDetectionTime = 0L
        arFragment.arSceneView.scene.addOnUpdateListener {
            arFragment.arSceneView.arFrame?.let { frame ->
                val currentTime = System.currentTimeMillis()
                if (isScanning && currentTime - lastDetectionTime > DETECTION_INTERVAL) {
                    onUpdateFrame(frame)
                    lastDetectionTime = currentTime
                }
                updateTrackingStatus(frame)
            }
        }

        arFragment.setOnTapArPlaneListener { hitResult, plane, _ ->
            if (!isLoadingModel) {
                onPlaneTapped(hitResult, plane)
            }
        }
    }

    private fun loadBracketModel() {
        isLoadingModel = true
        updateStatus("Loading bracket model...")

        try {
            val assetFiles = assets.list("") ?: emptyArray()
            val hasModel = assetFiles.any { it.equals(BRACKET_MODEL_NAME, ignoreCase = true) }

            if (hasModel) {
                Log.d(TAG, "Loading 3D bracket model from assets...")

                ModelRenderable.builder()
                    .setSource(this, Uri.parse(BRACKET_MODEL_NAME))
                    .build()
                    .thenAccept { renderable ->
                        bracketRenderable = renderable
                        isLoadingModel = false
                        Log.d(TAG, "3D bracket model loaded successfully")
                        runOnUiThread {
                            updateStatus("Ready - Tap to start")
                            Toast.makeText(this, "3D Bracket Model Loaded", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .exceptionally { throwable ->
                        Log.e(TAG, "Failed to load bracket model: ${throwable?.message}")
                        createFallbackBracket()
                        return@exceptionally null
                    }
            } else {
                Log.w(TAG, "No $BRACKET_MODEL_NAME found in assets, using fallback")
                createFallbackBracket()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bracket model: ${e.message}")
            createFallbackBracket()
        }
    }

    private fun createFallbackBracket() {
        MaterialFactory.makeOpaqueWithColor(this, Color(0.85f, 0.85f, 0.9f, 1.0f))
            .thenAccept { material ->
                bracketRenderable = ShapeFactory.makeCube(
                    Vector3(0.004f, 0.004f, 0.002f),
                    Vector3.zero(),
                    material
                )
                isLoadingModel = false
                Log.d(TAG, "Using procedural bracket")
                runOnUiThread {
                    updateStatus("Ready - Using simple bracket")
                }
            }
            .exceptionally { throwable ->
                Log.e(TAG, "Failed to create fallback bracket: ${throwable?.message}")
                isLoadingModel = false
                return@exceptionally null
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
        val trackingState = frame.camera.trackingState

        runOnUiThread {
            when (trackingState) {
                TrackingState.TRACKING -> {
                    if (!isScanning) {
                        updateStatus("Ready - Tap Start Scanning")
                    }
                }
                TrackingState.PAUSED -> {
                    updateStatus("Move device slowly")
                }
                TrackingState.STOPPED -> {
                    updateStatus("Tracking lost")
                }
                else -> {}
            }
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
            // Camera image not yet available
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
            Toast.makeText(this, "Detection system initialized", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startScanning() {
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

        try {
            val anchorNode = AnchorNode(anchor)
            anchorNode.setParent(arFragment.arSceneView.scene)

            val bracketNode = TransformableNode(arFragment.transformationSystem)
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
            Log.e(TAG, "Error placing bracket: ${e.message}")
            Toast.makeText(this, "Failed to place bracket", Toast.LENGTH_SHORT).show()
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
            val view = arFragment.arSceneView
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
            Log.e(TAG, "Error capturing scene: ${e.message}")
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
            Log.e(TAG, "Error saving: ${e.message}")
            runOnUiThread {
                Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resetScene() {
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
    }

    private fun showInfoDialog() {
        val modelStatus = when {
            isLoadingModel -> "Loading..."
            bracketRenderable != null -> "Loaded"
            else -> "Fallback Mode"
        }

        val mlStatus = if (teethDetector.hasModel()) "ML Active" else "Simulation"

        AlertDialog.Builder(this)
            .setTitle("Dental AR Bracket Placer")
            .setMessage(
                """
                STATUS:
                Bracket Model: $modelStatus
                Teeth Detection: $mlStatus
                Brackets Placed: ${bracketNodes.size}
                
                HOW TO USE:
                1. Tap Start Scanning
                2. Point at dental model
                3. Tap surface to place bracket
                4. Use controls to adjust
                5. Tap bracket to select
                6. Capture to save
                """.trimIndent()
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateStatus(message: String) {
        runOnUiThread {
            binding.statusTextView.text = message
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        teethDetector.close()
    }
}

class CustomArFragment : ArFragment() {

    override fun onAttach(context: Context) {
        super.onAttach(context)

        // Configure ARCore session using the new API
        setOnSessionConfigurationListener { session, config ->
            config.lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
            config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            config.focusMode = Config.FocusMode.AUTO

            Log.d("CustomArFragment", "AR Session configured")
        }
    }
}
