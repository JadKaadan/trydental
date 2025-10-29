package com.dental.arapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import android.graphics.Canvas
import android.graphics.Color



data class Detection(
    val boundingBox: RectF,
    val confidence: Float,
    val classId: Int = 0,
    val label: String = "tooth"
)

class TeethDetector(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var isModelLoaded = false
    private var lastPreprocessInfo: PreprocessInfo? = null

    companion object {
        private const val TAG = "TeethDetector"
        private const val MODEL_NAME = "tooth_detection_yolov8.tflite"
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val IOU_THRESHOLD = 0.45f
    }
    private data class PreprocessInfo(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val srcWidth: Int,
        val srcHeight: Int
    )

    init {
        try {
            val modelBuffer = loadModelFile()
            interpreter = Interpreter(modelBuffer)
            isModelLoaded = true
            Log.i(TAG, "Model loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model: ${e.message}")
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

    fun detect(image: Image): List<Detection> {
        return if (isModelLoaded && interpreter != null) {
            detectWithModel(image)
        } else {
            simulateDetection()
        }
    }

    private fun detectWithModel(image: Image): List<Detection> {
        try {
            val inputBuffer = preprocessImage(image)
            val outputBuffer = Array(1) { Array(8400) { FloatArray(84) } }
            interpreter?.run(inputBuffer, outputBuffer)
            return postprocessResults(outputBuffer[0])

        } catch (e: Exception) {
            Log.e(TAG, "Detection error: ${e.message}")
            return emptyList()
        }

    }

    private fun preprocessImage(image: Image): ByteBuffer {
        val bitmap = imageToBitmap(image)
        val srcWidth = bitmap.width
        val srcHeight = bitmap.height

        if (srcWidth <= 0 || srcHeight <= 0) {
            lastPreprocessInfo = null
            return ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3).apply {
                order(ByteOrder.nativeOrder())
            }
        }

        val scale = minOf(INPUT_SIZE.toFloat() / srcWidth, INPUT_SIZE.toFloat() / srcHeight)
        val scaledWidth = (srcWidth * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (srcHeight * scale).toInt().coerceAtLeast(1)

        val letterboxBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxBitmap)
        canvas.drawColor(Color.BLACK)

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        val padX = (INPUT_SIZE - scaledWidth) / 2f
        val padY = (INPUT_SIZE - scaledHeight) / 2f
        canvas.drawBitmap(scaledBitmap, padX, padY, null)

        lastPreprocessInfo = PreprocessInfo(
            scale = scale,
            padX = padX,
            padY = padY,
            srcWidth = srcWidth,
            srcHeight = srcHeight
        )

        val buffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        buffer.order(ByteOrder.nativeOrder())


        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        letterboxBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        var pixelIndex = 0
        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val pixel = pixels[pixelIndex++]
                val r = (pixel shr 16 and 0xFF) / 255f
                val g = (pixel shr 8 and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                buffer.putFloat(r)
                buffer.putFloat(g)
                buffer.putFloat(b)
            }
        }

        buffer.rewind()
        return buffer
    }

    private fun postprocessResults(output: Array<FloatArray>): List<Detection> {
        val preprocessInfo = lastPreprocessInfo
        val detections = mutableListOf<Detection>()
        for (i in output.indices) {
            val row = output[i]
            if (row.size < 5) continue

            val objectness = row[4]

            val classScores = if (row.size > 5) row.copyOfRange(5, row.size) else floatArrayOf(1f)
            var bestClass = 0
            var bestScore = 0f
            for (index in classScores.indices) {
                val score = classScores[index]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = index
                }
            }


            val confidence = objectness * bestScore
            if (confidence > CONFIDENCE_THRESHOLD) {
                val cx = row[0]
                val cy = row[1]
                val w = row[2]
                val h = row[3]

                val left = (cx - w / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
                val top = (cy - h / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
                val right = (cx + w / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
                val bottom = (cy + h / 2f).coerceIn(0f, INPUT_SIZE.toFloat())

                val normalizedBox = if (preprocessInfo != null) {
                    val invScale = 1f / preprocessInfo.scale
                    val srcLeft = ((left - preprocessInfo.padX) * invScale).coerceIn(0f, preprocessInfo.srcWidth.toFloat())
                    val srcTop = ((top - preprocessInfo.padY) * invScale).coerceIn(0f, preprocessInfo.srcHeight.toFloat())
                    val srcRight = ((right - preprocessInfo.padX) * invScale).coerceIn(0f, preprocessInfo.srcWidth.toFloat())
                    val srcBottom = ((bottom - preprocessInfo.padY) * invScale).coerceIn(0f, preprocessInfo.srcHeight.toFloat())

                    RectF(
                        (srcLeft / preprocessInfo.srcWidth).coerceIn(0f, 1f),
                        (srcTop / preprocessInfo.srcHeight).coerceIn(0f, 1f),
                        (srcRight / preprocessInfo.srcWidth).coerceIn(0f, 1f),
                        (srcBottom / preprocessInfo.srcHeight).coerceIn(0f, 1f)
                    )
                } else {
                    RectF(
                        left / INPUT_SIZE,
                        top / INPUT_SIZE,
                        right / INPUT_SIZE,
                        bottom / INPUT_SIZE
                        )

                }

            if (normalizedBox.right > normalizedBox.left && normalizedBox.bottom > normalizedBox.top) {
                detections.add(
                    Detection(
                        boundingBox = normalizedBox,
                        confidence = confidence,
                        classId = bestClass,
                        label = "tooth"
                    )
                )
            }
            }
        }
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

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val intersectionLeft = maxOf(a.left, b.left)
        val intersectionTop = maxOf(a.top, b.top)
        val intersectionRight = minOf(a.right, b.right)
        val intersectionBottom = minOf(a.bottom, b.bottom)
        val intersectionArea = maxOf(0f, intersectionRight - intersectionLeft) * maxOf(0f, intersectionBottom - intersectionTop)
        val unionArea = a.width() * a.height() + b.width() * b.height() - intersectionArea
        return if (unionArea == 0f) 0f else intersectionArea / unionArea
    }

    private fun simulateDetection(): List<Detection> {
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

    private fun imageToBitmap(image: Image): Bitmap {
        val nv21 = yuv420ToNV21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    }

    private fun yuv420ToNV21(image: Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)

        val pixelStride = image.planes[1].pixelStride
        val rowStride = image.planes[1].rowStride
        val width = image.width
        val height = image.height

        val uBytes = ByteArray(uSize)
        val vBytes = ByteArray(vSize)
        uBuffer.get(uBytes)
        vBuffer.get(vBytes)

        var offset = ySize
        for (row in 0 until height / 2) {
            var column = 0
            while (column < width / 2) {
                val uvIndex = row * rowStride + column * pixelStride
                val vValue = if (uvIndex < vBytes.size) vBytes[uvIndex] else 0
                val uValue = if (uvIndex < uBytes.size) uBytes[uvIndex] else 0
                nv21[offset++] = vValue
                nv21[offset++] = uValue
                column++
            }
        }

        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        return nv21
    }
}
