package com.example.whatsapp_summarizer.util

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

class LocalModelManager(private val context: Context) {

    private var interpreter: Interpreter? = null
    private val modelFile = File(context.filesDir, "local_model.tflite")
    private val legacyModelFile = File(context.filesDir, "gemma-2b-it-gpu-int4.bin")

    companion object {
        private const val TAG = "LocalModelManager"
        private const val MIN_MODEL_SIZE_BYTES = 50 * 1024 * 1024
        private const val MAX_MODEL_SIZE_BYTES = Integer.MAX_VALUE.toLong()
        private const val MAX_GENERATION_LENGTH = 128
        private const val TEMPERATURE = 0.7f
    }

    fun isModelAvailable(): Boolean {
        val activeFile = getActiveModelFile()
        return activeFile != null && activeFile.length() >= MIN_MODEL_SIZE_BYTES
    }

    fun getModelSize(): String {
        val file = getActiveModelFile()
        return if (file != null) {
            "${file.length() / (1024 * 1024)} MB"
        } else "Not imported"
    }

    private fun getActiveModelFile(): File? = when {
        modelFile.exists() && modelFile.length() >= MIN_MODEL_SIZE_BYTES -> modelFile
        legacyModelFile.exists() && legacyModelFile.length() >= MIN_MODEL_SIZE_BYTES -> legacyModelFile
        else -> null
    }

    suspend fun importModelFromUri(uri: Uri, fileName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            modelFile.delete()
            legacyModelFile.delete()

            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Could not open input stream"))

            val size = modelFile.length()
            Log.d(TAG, "File copied: $size bytes (${size / (1024 * 1024)} MB)")

            val validationError = validateTfliteModel(modelFile)
            if (validationError != null) {
                modelFile.delete()
                return@withContext Result.failure(Exception(validationError))
            }

            val loadError = testLoadModel(modelFile)
            if (loadError != null) {
                modelFile.delete()
                return@withContext Result.failure(Exception("Model file is not compatible: $loadError"))
            }

            Result.success("${size / (1024 * 1024)} MB")
        } catch (e: Exception) {
            modelFile.delete()
            Result.failure(e)
        }
    }

    private fun validateTfliteModel(file: File): String? {
        val size = file.length()
        if (size < MIN_MODEL_SIZE_BYTES) return "File too small (${size / (1024 * 1024)} MB)"
        if (size > MAX_MODEL_SIZE_BYTES) return "Model too large (${size / (1024 * 1024)} MB)"

        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(8)
                fis.read(header)
                val magic = String(header, 4, 4)
                if (magic != "TFL3") "Not a valid TFLite model" else null
            }
        } catch (e: Exception) {
            "Cannot read file: ${e.message}"
        }
    }

    private fun testLoadModel(file: File): String? {
        return try {
            val options = Interpreter.Options().apply { setNumThreads(2) }
            val buffer = loadModelFileDirect(file)
            val testInterpreter = Interpreter(buffer, options)
            val inShape = testInterpreter.getInputTensor(0)?.shape()?.contentToString() ?: "?"
            val outShape = testInterpreter.getOutputTensor(0)?.shape()?.contentToString() ?: "?"
            Log.d(TAG, "Model OK. Input: $inShape, Output: $outShape")
            testInterpreter.close()
            null
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun loadModel(): Result<String> {
        return try {
            val activeFile = getActiveModelFile()
                ?: return Result.failure(Exception("No model found"))

            val options = Interpreter.Options().apply { setNumThreads(2) }
            interpreter = Interpreter(loadModelFileDirect(activeFile), options)
            Log.d(TAG, "Model loaded")
            Result.success("Loaded")
        } catch (e: Exception) {
            Result.failure(Exception("${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    fun unloadModel() {
        interpreter?.close()
        interpreter = null
    }

    fun summarize(conversation: String, inHebrew: Boolean = false): String {
        val interp = this.interpreter
            ?: return if (inHebrew) "שגיאה: המודל לא נטען" else "Error: Model not loaded"

        return try {
            val inputTensor = interp.getInputTensor(0)
            val outputTensor = interp.getOutputTensor(0)
            val inputShape = inputTensor.shape()
            val outputShape = outputTensor.shape()

            Log.d(TAG, "Input: ${inputShape.contentToString()}, type=${inputTensor.dataType()}")
            Log.d(TAG, "Output: ${outputShape.contentToString()}, type=${outputTensor.dataType()}")

            val vocabSize = when (outputShape.size) {
                3 -> outputShape[2]
                2 -> outputShape[1]
                else -> return "Unsupported output shape: ${outputShape.contentToString()}"
            }

            if (vocabSize > 500000) {
                return "Vocab size ($vocabSize) too large. SentencePiece tokenizer required."
            }

            val prompt = if (inHebrew) {
                "<start_of_turn>user\nסכם את השיחה הבאה בעברית:\n\n$conversation\n<end_of_turn>\n<start_of_turn>model\n"
            } else {
                "<start_of_turn>user\nSummarize the following conversation concisely:\n\n$conversation\n<end_of_turn>\n<start_of_turn>model\n"
            }

            val tokens = mutableListOf<Int>(2)
            prompt.toByteArray(Charsets.UTF_8).forEach { byte ->
                tokens.add(byte.toInt() and 0xFF)
            }

            val isSingleTokenModel = inputShape.size == 2 && inputShape[1] == 1

            val generated = mutableListOf<Int>()
            var currentToken = tokens.firstOrNull() ?: 2

            for (step in 0 until MAX_GENERATION_LENGTH) {
                val inputArray: Array<IntArray> = if (isSingleTokenModel) {
                    Array(1) { intArrayOf(currentToken) }
                } else {
                    val seqLen = inputShape[1]
                    val padded = IntArray(seqLen) { 0 }
                    tokens.take(seqLen).forEachIndexed { i, v -> padded[i] = v }
                    Array(1) { padded }
                }

                val logits: FloatArray = when (outputShape.size) {
                    3 -> {
                        val output = Array(1) { Array(outputShape[1]) { FloatArray(vocabSize) { 0f } } }
                        interp.run(inputArray, output)
                        output[0][0]
                    }
                    2 -> {
                        val output = Array(1) { FloatArray(vocabSize) { 0f } }
                        interp.run(inputArray, output)
                        output[0]
                    }
                    else -> return "Unsupported output shape: ${outputShape.contentToString()}"
                }

                val nextToken = sampleToken(logits, TEMPERATURE)
                if (nextToken == 1 || nextToken == 0 || nextToken == 3) break

                generated.add(nextToken)
                currentToken = nextToken

                if (!isSingleTokenModel) {
                    tokens.add(nextToken)
                }
            }

            val bytes = generated.map { it.toByte() }.toByteArray()
            val summary = String(bytes, Charsets.UTF_8)
                .replace(Regex("<[^>]+>"), "")
                .trim()

            if (summary.isBlank()) {
                if (inHebrew) "המודל רץ אבל הפלט ריק. נדרש טוקניזר SentencePiece. השתמש ב-OpenAI."
                else "Model ran but output is empty. SentencePiece tokenizer required. Use OpenAI."
            } else summary

        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM during inference", e)
            if (inHebrew) "נגמר הזיכרון. המודל גדול מדי להרצה על מכשיר זה. השתמש ב-OpenAI."
            else "Out of memory. Model too large for this device. Use OpenAI."
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
            if (inHebrew) "שגיאה: ${e.message}" else "Error: ${e.message}"
        }
    }

    private fun sampleToken(logits: FloatArray, temperature: Float): Int {
        val scaled = logits.map { it / temperature }.toFloatArray()
        val maxLogit = scaled.maxOrNull() ?: 0f
        val expValues = scaled.map { exp(it - maxLogit) }
        val sumExp = expValues.sum()
        val probs = expValues.map { it / sumExp }
        val rand = Math.random()
        var cum = 0.0
        for (i in probs.indices) {
            cum += probs[i]
            if (rand < cum) return i
        }
        return probs.indices.maxByOrNull { probs[it] } ?: 0
    }

    fun deleteModel() {
        unloadModel()
        modelFile.delete()
        legacyModelFile.delete()
    }

    private fun loadModelFileDirect(file: File): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(file.length().toInt())
        buffer.order(ByteOrder.nativeOrder())
        FileInputStream(file).use { fis ->
            val channel = fis.channel
            while (buffer.hasRemaining()) {
                channel.read(buffer)
            }
        }
        buffer.rewind()
        return buffer
    }
}