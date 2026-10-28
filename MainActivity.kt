package com.example.hindiaassistant

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class ChatLine(val who: String, val message: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TrinetraScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrinetraScreen() {
    val context = LocalContext.current
    val lines = remember {
        mutableStateListOf(
            ChatLine("TRINETRA", "नमस्ते! मैं Trinetra AI हूँ। सवाल लिखें या माइक्रोफ़ोन से बोलें।")
        )
    }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var speakReplies by remember { mutableStateOf(true) }
    var voiceLanguage by remember { mutableStateOf("hi-IN") }
    var ttsReady by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val tts = remember {
        TextToSpeech(context) { status -> ttsReady = status == TextToSpeech.SUCCESS }
    }
    DisposableEffect(tts) {
        onDispose { tts.stop(); tts.shutdown() }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) input = spoken
        }
    }

    fun startListening() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLanguage)
                putExtra(RecognizerIntent.EXTRA_PROMPT, if (voiceLanguage == "hi-IN") "Trinetra को अपना सवाल बताइए" else "Ask Trinetra your question")
            }
            speechLauncher.launch(intent)
        } catch (_: Exception) {
            lines.add(ChatLine("TRINETRA", "Speech recognition उपलब्ध नहीं है। कृपया सवाल टाइप करें।"))
        }
    }

    fun openIntent(intent: Intent, successMessage: String) {
        try {
            context.startActivity(intent)
            lines.add(ChatLine("TRINETRA", successMessage))
        } catch (_: ActivityNotFoundException) {
            lines.add(ChatLine("TRINETRA", "इस डिवाइस पर संबंधित ऐप या सुविधा उपलब्ध नहीं है।"))
        } catch (_: Exception) {
            lines.add(ChatLine("TRINETRA", "यह कार्रवाई पूरी नहीं हो सकी।"))
        }
    }

    fun runLocalCommand(question: String): Boolean {
        val q = question.lowercase(Locale.ROOT)
        return when {
            listOf("settings kholo", "सेटिंग खोल", "सेटिंग्स खोल").any { q.contains(it) } -> {
                openIntent(Intent(android.provider.Settings.ACTION_SETTINGS), "सेटिंग्स खोलने की कोशिश की।")
                true
            }
            listOf("camera kholo", "कैमरा खोल").any { q.contains(it) } -> {
                openIntent(Intent("android.media.action.IMAGE_CAPTURE"), "कैमरा खोलने की कोशिश की।")
                true
            }
            listOf("youtube kholo", "यूट्यूब खोल").any { q.contains(it) } -> {
                val i = context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                if (i != null) openIntent(i, "YouTube खोलने की कोशिश की।")
                else openIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com")), "YouTube वेबसाइट खोली।")
                true
            }
            listOf("browser kholo", "ब्राउज़र खोल", "internet kholo").any { q.contains(it) } -> {
                openIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")), "ब्राउज़र खोलने की कोशिश की।")
                true
            }
            listOf("alarm lagao", "alarm set", "अलार्म लगाओ", "अलार्म सेट").any { q.contains(it) } -> {
                // Opens the system alarm UI; it does not silently set an alarm.
                openIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS), "घड़ी ऐप में अलार्म स्क्रीन खोली। वहाँ समय की पुष्टि करें।")
                true
            }
            listOf("calendar kholo", "कैलेंडर खोल").any { q.contains(it) } -> {
                openIntent(Intent(Intent.ACTION_VIEW).setData(Uri.parse("content://com.android.calendar/time")), "कैलेंडर खोलने की कोशिश की।")
                true
            }
            listOf("maps kholo", "मैप खोल", "नक्शा खोल").any { q.contains(it) } -> {
                openIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com")), "मैप खोलने की कोशिश की।")
                true
            }
            else -> false
        }
    }

    fun sendQuestion() {
        val question = input.trim()
        if (question.isEmpty() || busy) return
        input = ""
        lines.add(ChatLine("आप", question))
        if (runLocalCommand(question)) return

        busy = true
        scope.launch {
            val answer = withContext(Dispatchers.IO) { askBackend(question) }
            lines.add(ChatLine("TRINETRA", answer))
            if (speakReplies && ttsReady) {
                val locale = if (voiceLanguage == "hi-IN") Locale("hi", "IN") else Locale.US
                val languageStatus = tts.setLanguage(locale)
                if (languageStatus >= TextToSpeech.LANG_AVAILABLE) {
                    tts.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "trinetra-reply")
                }
            }
            busy = false
        }
    }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("TRINETRA AI") }) }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(12.dp)) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(lines) { line ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(line.who, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(line.message)
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = speakReplies, onCheckedChange = { speakReplies = it })
                        Text("जवाब आवाज़ में सुनें")
                    }
                    TextButton(onClick = {
                        voiceLanguage = if (voiceLanguage == "hi-IN") "en-US" else "hi-IN"
                    }) {
                        Text(if (voiceLanguage == "hi-IN") "भाषा: हिन्दी" else "Language: English")
                    }
                }
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("अपना सवाल लिखें") },
                    enabled = !busy, maxLines = 4
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f), enabled = !busy,
                        onClick = { startListening() }
                    ) { Text("🎙 बोलें") }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = input.isNotBlank() && !busy,
                        onClick = { sendQuestion() }
                    ) { Text(if (busy) "सोच रहा हूँ…" else "भेजें") }
                }
            }
        }
    }
}

private fun askBackend(question: String): String {
    val apiUrl = BuildConfig.TRINETRA_API_URL
    if (apiUrl.contains("YOUR-HTTPS-BACKEND.example.com")) {
        return "AI chat अभी configure नहीं है। Backend deploy करके TRINETRA_API_URL सेट करें; तब तक local phone commands काम कर सकते हैं।"
    }
    return try {
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        connection.outputStream.use {
            it.write(JSONObject().put("message", question).toString().toByteArray(Charsets.UTF_8))
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) "Server error ($code). Backend logs aur API configuration जाँचें।"
        else JSONObject(body).optString("reply", "जवाब खाली मिला।")
    } catch (e: Exception) {
        "कनेक्शन नहीं हो पाया। API_URL और backend की स्थिति जाँचें। विवरण: ${e.message ?: "unknown error"}"
    }
}
