package com.stellon.mobile.sample

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.stellon.mobile.sdk.BitnetClient
import com.stellon.mobile.sdk.GenerationParameters
import com.stellon.mobile.sdk.ModelSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var output: TextView
    private lateinit var sendButton: Button
    private lateinit var downloadButton: Button
    private lateinit var inputField: EditText
    private lateinit var loader: ProgressBar
    private lateinit var client: BitnetClient
    
    private val targetModel = ModelSource.officialBitnetB1582B4T()
    private var isModelLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = BitnetClient.create(this)

        setupUI()
        updateUIVisibility()
    }

    private fun setupUI() {
        inputField = EditText(this).apply {
            hint = "Ask something..."
            setPadding(32, 32, 32, 32)
        }

        loader = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            visibility = View.GONE
        }

        output = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(32, 32, 32))
            setPadding(32, 24, 32, 24)
            text = "Initializing..."
        }

        sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener { runInference() }
        }

        downloadButton = Button(this).apply {
            text = "Download Model (~1.2GB)"
            setOnClickListener { startDownload() }
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(inputField)
            addView(sendButton)
            addView(downloadButton)
            addView(
                loader,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = android.view.Gravity.CENTER
                    topMargin = 16
                }
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(controls)
            addView(
                ScrollView(this@MainActivity).apply { addView(output) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)
    }

    private fun updateUIVisibility() {
        val downloaded = client.models.isDownloaded(targetModel)
        
        downloadButton.visibility = if (downloaded) View.GONE else View.VISIBLE
        sendButton.visibility = if (downloaded) View.VISIBLE else View.GONE
        inputField.visibility = if (downloaded) View.VISIBLE else View.GONE
        
        if (downloaded) {
            output.text = "Model is ready.\n\nType a prompt and tap Send."
        } else {
            output.text = "Model not found.\n\nPlease download the BitNet B1.58 2B model to begin."
        }
    }

    private fun startDownload() {
        scope.launch {
            downloadButton.isEnabled = false
            loader.visibility = View.VISIBLE
            output.text = "Starting download..."
            
            try {
                // This triggers the download via ModelManager
                client.loadModel(targetModel) { progress ->
                    val percent = progress.fraction?.let { "${(it * 100).toInt()}%" }
                        ?: "${progress.downloadedBytes / (1024 * 1024)} MB"
                    scope.launch {
                        output.text = "Downloading: $percent\n\nPlease keep the app open."
                    }
                }
                isModelLoaded = true
                updateUIVisibility()
            } catch (e: Exception) {
                output.text = "Download failed: ${e.message}"
                downloadButton.isEnabled = true
            } finally {
                loader.visibility = View.GONE
            }
        }
    }

    private fun runInference() {
        val prompt = inputField.text.toString().trim()
        if (prompt.isEmpty()) return

        scope.launch {
            sendButton.isEnabled = false
            inputField.isEnabled = false
            loader.visibility = View.VISIBLE
            
            try {
                // Ensure model is loaded into the native runtime if it wasn't just downloaded
                if (!isModelLoaded) {
                    output.text = "Loading model into memory..."
                    client.loadModel(targetModel)
                    isModelLoaded = true
                }

                output.text = "Prompt: $prompt\n\n"
                val session = client.createChatSession(systemPrompt = "You are running locally on Android.")
                session.sendStreaming(
                    prompt,
                    GenerationParameters(maxTokens = 256),
                ).collect { chunk ->
                    if (chunk.index >= 0) {
                        output.append(chunk.text)
                    }
                }
                output.append("\n\n[Done]")
            } catch (error: Throwable) {
                output.text = "Error: ${error.message}"
                isModelLoaded = false // Reset in case of load failure
            } finally {
                sendButton.isEnabled = true
                inputField.isEnabled = true
                loader.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        client.close()
        super.onDestroy()
    }
}
