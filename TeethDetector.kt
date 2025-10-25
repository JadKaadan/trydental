package com.dental.arapp

import android.content.Context
import android.graphics.RectF
import android.media.Image
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Detection result containing bounding box and confidence
 */
data class Detection(
    val boundingBox: RectF,
    val confidence: Float,
    val classId: Int = 0,
    val label: String = "tooth"
)

/**
 * Teeth detection using TensorFlow Lite YOLOv8 model
 * Falls back to simulated detection if model not available
 */
class TeethDetector(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var isModelLoaded = false

    companion object {
        private const val TAG = "TeethDetector"
        private const val MODEL_NAME = "tooth_detection_yolov8.tflite"
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val IOU_THRESHOLD = 0.45f
    }

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val modelFile = loadModelFile()
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelFile, options)
            isModelLoaded = true
            Log.d(TAG, "ML model loaded successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load ML model, using simulated detection: ${e.message}")
            isModelLoaded = false
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_NAME)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Detect teeth in camera image
     */
    fun detect(image: Image): List<Detection> {
        return if (isModelLoaded && interpreter != null) {
            detectWithModel(image)
        } else {
            simulateDetection()
        }
    }

    private fun detectWithModel(image: Image): List<Detection> {
        try {
            // Prepare input buffer
            val inputBuffer = preprocessImage(image)

            // Prepare output buffer (YOLOv8 format: [1, 84, 8400])
            // 84 = 4 (bbox) + 80 (classes)
            val outputBuffer = Array(1) { Array(84) { FloatArray(8400) } }

            // Run inference
            interpreter?.run(inputBuffer, outputBuffer)

            // Post-process results
            return postprocessResults(outputBuffer[0])

        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}")
            return emptyList()
        }
    }

    private fun preprocessImage(image: Image): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())

        // Convert Image to ByteBuffer (simplified - should handle YUV->RGB conversion)
        // This is a placeholder - actual implementation needs proper image conversion
        val planes = image.planes
        // TODO: Implement proper YUV to RGB conversion and resizing

        return buffer
    }

    private fun postprocessResults(output: Array<FloatArray>): List<Detection> {
        val detections = mutableListOf<Detection>()

        // Parse YOLOv8 output format
        for (i in 0 until 8400) {
            val confidence = output[4][i] // First class confidence

            if (confidence > CONFIDENCE_THRESHOLD) {
                val cx = output[0][i]
                val cy = output[1][i]
                val w = output[2][i]
                val h = output[3][i]

                val left = (cx - w / 2) / INPUT_SIZE
                val top = (cy - h / 2) / INPUT_SIZE
                val right = (cx + w / 2) / INPUT_SIZE
                val bottom = (cy + h / 2) / INPUT_SIZE

                detections.add(
                    Detection(
                        boundingBox = RectF(left, top, right, bottom),
                        confidence = confidence,
                        classId = 0,
                        label = "tooth"
                    )
                )
            }
        }

        // Apply Non-Maximum Suppression
        return applyNMS(detections)
    }

    private fun applyNMS(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        val sorted = detections.sortedByDescending { it.confidence }
        val selected = mutableListOf<Detection>()

        for (detection in sorted) {
            var shouldSelect = true
            for (selectedDetection in selected) {
                if (calculateIoU(detection.boundingBox, selectedDetection.boundingBox) > IOU_THRESHOLD) {
                    shouldSelect = false
                    break
                }
            }
            if (shouldSelect) {
                selected.add(detection)
            }
        }

        return selected
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = maxOf(box1.left, box2.left)
        val intersectionTop = maxOf(box1.top, box2.top)
        val intersectionRight = minOf(box1.right, box2.right)
        val intersectionBottom = minOf(box1.bottom, box2.bottom)

        if (intersectionRight < intersectionLeft || intersectionBottom < intersectionTop) {
            return 0f
        }

        val intersectionArea = (intersectionRight - intersectionLeft) * (intersectionBottom - intersectionTop)
        val box1Area = box1.width() * box1.height()
        val box2Area = box2.width() * box2.height()
        val unionArea = box1Area + box2Area - intersectionArea

        return intersectionArea / unionArea
    }

    /**
     * Simulate detection when ML model is not available
     * Returns random detections for testing
     */
    private fun simulateDetection(): List<Detection> {
        // Simulate 4-8 teeth detected with random positions
        val numTeeth = (4..8).random()
        return List(numTeeth) { i ->
            Detection(
                boundingBox = RectF(
                    (i * 0.1f).coerceIn(0f, 0.9f),
                    0.3f + (i % 2) * 0.2f,
                    (i * 0.1f + 0.15f).coerceIn(0.1f, 1f),
                    0.5f + (i % 2) * 0.2f
                ),
                confidence = 0.75f + (Math.random() * 0.2f).toFloat(),
                classId = 0,
                label = "tooth"
            )
        }
    }

    fun hasModel(): Boolean = isModelLoaded

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
