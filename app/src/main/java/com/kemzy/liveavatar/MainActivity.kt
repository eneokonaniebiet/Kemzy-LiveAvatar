package com.kemzy.liveavatar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.kemzy.liveavatar.camera.CameraController
import com.kemzy.liveavatar.cloud.CloudRenderClient
import com.kemzy.liveavatar.camera.DriverMotion
import com.kemzy.liveavatar.camera.FaceTracker
import com.kemzy.liveavatar.engine.EngineState
import com.kemzy.liveavatar.engine.LiveAvatarEngine
import com.kemzy.liveavatar.engine.MotionControls
import com.kemzy.liveavatar.models.ModelDiscovery
import com.kemzy.liveavatar.models.ModelImporter
import com.kemzy.liveavatar.security.PasscodeStore
import com.kemzy.liveavatar.source.SourceKind
import com.kemzy.liveavatar.source.SourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ProductPage(val label: String) {
    STUDIO("Studio"), SOURCES("Sources"), CREATIONS("Creations"), PRICING("Pricing"), HELP("Help Center"), SETTINGS("Settings"), PRIVACY("Privacy"), TERMS("Terms"), ABOUT("About Kémzy")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KemzyApp(PasscodeStore(this)) }
    }
}

@Composable
private fun KemzyApp(store: PasscodeStore) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("kemzy_product", Context.MODE_PRIVATE) }
    var onboardingDone by remember { mutableStateOf(prefs.getBoolean("onboarding_done", false)) }
    var unlocked by remember { mutableStateOf(false) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            when {
                !onboardingDone -> OnboardingScreen { prefs.edit().putBoolean("onboarding_done", true).apply(); onboardingDone = true }
                !unlocked -> PasscodeScreen(store) { unlocked = true }
                else -> ProductShell()
            }
        }
    }
}

@Composable
private fun OnboardingScreen(onDone: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val cards = listOf(
        "One face. A living avatar." to "Turn a portrait into a responsive Kémzy avatar driven by your live facial movement.",
        "Move naturally." to "Kémzy follows head direction, eyes, blinking, mouth movement and expression through the phone camera.",
        "Create for real life." to "Use image or video sources, prepare your avatar locally, then use the studio for calls, recording and creator workflows."
    )
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("KÉMZY", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Kémzy àvátâr", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Text(cards[page].first, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text(cards[page].second, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("${page + 1} / ${cards.size}", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { if (page == cards.lastIndex) onDone() else page++ }, modifier = Modifier.fillMaxWidth()) {
            Text(if (page == cards.lastIndex) "Get started" else "Continue")
        }
    }
}

@Composable
private fun PasscodeScreen(store: PasscodeStore, onUnlocked: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Private studio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Enter your Kémzy passcode to continue.")
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(code, { code = it.take(12); error = "" }, label = { Text("Passcode") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Button(onClick = { if (store.verify(code.toCharArray())) { onUnlocked(); code = "" } else error = "Incorrect passcode." }, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
        if (error.isNotEmpty()) Text(error, Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ProductShell() {
    var page by remember { mutableStateOf(ProductPage.STUDIO) }
    Scaffold(bottomBar = {
        NavigationBar(Modifier.navigationBarsPadding()) {
            listOf(ProductPage.STUDIO, ProductPage.SOURCES, ProductPage.CREATIONS, ProductPage.SETTINGS).forEach { item ->
                NavigationBarItem(selected = page == item, onClick = { page = item }, icon = { Text(item.label.take(1)) }, label = { Text(item.label) })
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                ProductPage.STUDIO -> StudioScreen()
                ProductPage.SOURCES -> SourcesScreen()
                ProductPage.CREATIONS -> InfoScreen("Creations", "Your saved avatar sessions and exported work will appear here.")
                ProductPage.PRICING -> InfoScreen("Pricing", "Kémzy pricing is prepared for the future account and billing service. No payment is collected by this offline build.")
                ProductPage.HELP -> HelpScreen()
                ProductPage.SETTINGS -> SettingsScreen(onPage = { page = it })
                ProductPage.PRIVACY -> InfoScreen("Privacy", "Kémzy is designed around local-first processing. Camera frames and imported models are used on-device by the live engine unless a future feature explicitly says otherwise.")
                ProductPage.TERMS -> InfoScreen("Terms", "Kémzy provides creative avatar tools. Availability and compatibility depend on device hardware, operating-system capabilities and supported integrations.")
                ProductPage.ABOUT -> InfoScreen("About Kémzy", "Kémzy àvátâr is an original live-avatar studio focused on expressive, private, real-time creation.")
            }
        }
    }
}

@Composable
private fun StudioScreen() {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cloud = remember { CloudRenderClient() }
    val tracker = remember { FaceTracker() }
    val camera = remember { CameraController(context, lifecycleOwner) }
    var permissionGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    var cloudReady by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Select a source to connect to Kémzy Cloud Neural Renderer") }
    var liveBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var previewView by remember { mutableStateOf<androidx.camera.view.PreviewView?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionGranted = it }
    LaunchedEffect(Unit) { if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val bitmap = runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (bitmap == null) {
                scope.launch(Dispatchers.Main) { status = "Unable to read that image" }
            } else {
                cloud.prepareSource(bitmap) { ok, message ->
                    scope.launch(Dispatchers.Main) {
                        cloudReady = ok
                        status = message
                    }
                    bitmap.recycle()
                }
            }
        }
    }

    val cameraSource = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap == null) return@rememberLauncherForActivityResult
        cloud.prepareSource(bitmap) { ok, message ->
            scope.launch(Dispatchers.Main) {
                cloudReady = ok
                status = message
            }
            bitmap.recycle()
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val frame = runCatching {
                MediaMetadataRetriever().let { r ->
                    r.setDataSource(context, uri)
                    r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST).also { r.release() }
                }
            }.getOrNull()
            if (frame == null) {
                scope.launch(Dispatchers.Main) { status = "Unable to read that video" }
            } else {
                cloud.prepareSource(frame) { ok, message ->
                    scope.launch(Dispatchers.Main) {
                        cloudReady = ok
                        status = if (ok) "Video source prepared — live cloud renderer is active" else message
                    }
                    frame.recycle()
                }
            }
        }
    }

    LaunchedEffect(cloudReady) {
        if (!cloudReady) return@LaunchedEffect
        while (cloudReady) {
            cloud.latestFrame()?.let { frame ->
                val copy = frame.copy(Bitmap.Config.ARGB_8888, false)
                val old = liveBitmap
                liveBitmap = copy
                old?.recycle()
            }
            cloud.error()?.let { status = it }
            delay(33)
        }
    }

    DisposableEffect(permissionGranted, cloudReady) {
        val view = previewView
        if (permissionGranted && view != null) {
            camera.startPreview(view) { image ->
                tracker.process(image) { motion ->
                    if (motion != null && cloudReady) cloud.sendMotion(motion, System.currentTimeMillis())
                }
            }
        }
        onDispose { camera.stop() }
    }

    DisposableEffect(Unit) {
        onDispose {
            camera.close()
            tracker.close()
            cloud.close()
            liveBitmap?.recycle()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Kémzy studio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(if (cloudReady) "CLOUD NEURAL LIVE" else "CLOUD READY", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(24.dp))) {
            AndroidView(
                factory = { androidx.camera.view.PreviewView(context).also { previewView = it } },
                modifier = Modifier.fillMaxSize()
            )
            liveBitmap?.let { frame ->
                Image(frame.asImageBitmap(), "Kémzy cloud neural avatar", Modifier.fillMaxSize())
            }
            if (!cloudReady) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(status, Modifier.padding(24.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(status, Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ imagePicker.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Photo") }
            Button({ videoPicker.launch("video/*") }, modifier = Modifier.weight(1f)) { Text("Video") }
            OutlinedButton({ cameraSource.launch(null) }, modifier = Modifier.weight(1f)) { Text("Camera") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { cloudReady = false; cloud.stop(); status = "Cloud session stopped" },
            enabled = cloudReady,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Stop cloud avatar") }
    }
}

@Composable
private fun SourcesScreen() {
    val context = LocalContext.current
    val repo = remember { SourceRepository(context) }
    val recent = remember { mutableStateListOf(*repo.recent().toTypedArray()) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Sources", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Choose a portrait, capture a face, or use a local video as the avatar reference.")
        Spacer(Modifier.height(16.dp))
        if (recent.isEmpty()) Text("No saved sources yet.") else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recent) { source -> Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(source.kind.name, fontWeight = FontWeight.Bold); Spacer(Modifier.size(12.dp)); Text(source.uri.toString(), maxLines = 1) } } }
        }
    }
}

@Composable
private fun SettingsScreen(onPage: (ProductPage) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { SettingCard("Security", "Passcode-first unlock is enabled. No biometric dependency.") }
        item { SettingCard("Camera & microphone", "Permissions are requested only when the studio needs them.") }
        item { SettingCard("Performance", "The live pipeline uses bounded frame processing and on-device inference.") }
        item { SettingCard("Models & storage", "Models are staged in private app storage. Your shared KemzyModels folder is never modified.") }
        item { SettingCard("External output", "Internal preview is supported. Universal WhatsApp virtual-camera output is not claimed until an Android-supported mechanism is verified.") }
        item { TextButton({ onPage(ProductPage.PRICING) }) { Text("Pricing") } }
        item { TextButton({ onPage(ProductPage.HELP) }) { Text("Help Center") } }
        item { TextButton({ onPage(ProductPage.PRIVACY) }) { Text("Privacy") } }
        item { TextButton({ onPage(ProductPage.TERMS) }) { Text("Terms") } }
        item { TextButton({ onPage(ProductPage.ABOUT) }) { Text("About Kémzy") } }
    }
}

@Composable
private fun SettingCard(title: String, body: String) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(title, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(body) } } }

@Composable
private fun HelpScreen() {
    val questions = listOf(
        "How do I import models?" to "Choose Import KemzyModels and select the existing KemzyModels folder. Kémzy copies required files into private storage for ONNX Runtime.",
        "Why is a face not detected?" to "Use a clear, front-facing portrait with the face visible and reasonably well lit.",
        "Can I use a video?" to "Yes. Kémzy accepts a local video and extracts a usable reference frame for the avatar workflow.",
        "Will WhatsApp use Kémzy as its camera?" to "Not automatically. Android apps cannot simply replace another app's camera with a CameraX preview; Kémzy will only expose this when a supported output mechanism is implemented and verified."
    )
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Help Center", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        items(questions) { (q, a) -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(q, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text(a) } } }
    }
}

@Composable
private fun InfoScreen(title: String, body: String) {
    Column(Modifier.fillMaxSize().padding(24.dp)) { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(14.dp)); Text(body, style = MaterialTheme.typography.bodyLarge) }
}
