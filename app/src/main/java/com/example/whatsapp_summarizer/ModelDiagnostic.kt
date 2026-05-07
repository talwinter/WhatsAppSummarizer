package com.example.whatsapp_summarizer

import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

object ModelDiagnostic {
    @JvmStatic
    fun main(args: Array<String>) {
        val modelFile = File("C:/Projects/WhatsReallyUp/gemma-2b-it-cpu-int4.bin")
        
        println("=== Model Diagnostic ===")
        println("File: ${modelFile.absolutePath}")
        println("Exists: ${modelFile.exists()}")
        println("Size: ${modelFile.length()} bytes (${modelFile.length() / (1024 * 1024)} MB)")
        
        // Check magic
        FileInputStream(modelFile).use { fis ->
            val header = ByteArray(8)
            fis.read(header)
            println("Magic: ${String(header, 4, 4)}")
        }
        
        try {
            val buffer = FileInputStream(modelFile).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, modelFile.length())
            }
            
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            
            val interpreter = Interpreter(buffer, options)
            println("SUCCESS: Model loaded!")
            println("Inputs: ${interpreter.inputTensorCount}")
            println("Outputs: ${interpreter.outputTensorCount}")
            
            for (i in 0 until interpreter.inputTensorCount) {
                val t = interpreter.getInputTensor(i)
                println("Input $i: shape=${t.shape().contentToString()}, type=${t.dataType()}")
            }
            for (i in 0 until interpreter.outputTensorCount) {
                val t = interpreter.getOutputTensor(i)
                println("Output $i: shape=${t.shape().contentToString()}, type=${t.dataType()}")
            }
            
            interpreter.close()
        } catch (e: Exception) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }
}
