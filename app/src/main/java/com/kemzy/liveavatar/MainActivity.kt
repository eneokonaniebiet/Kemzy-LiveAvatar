package com.kemzy.liveavatar

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kemzy.liveavatar.camera.CameraController
import com.kemzy.liveavatar.camera.DriverMotion
import com.kemzy.liveavatar.camera.FaceTracker
import com.kemzy.liveavatar.engine.EngineState
import com.kemzy.liveavatar.engine.LiveAvatarEngine
import com.kemzy.liveavatar.engine.MotionControls
import com.kemzy.liveavatar.models.ModelDiscovery
import com.kemzy.liveavatar.models.ModelImporter
import com.kemzy.liveavatar.security.PasscodeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KemzyApp(PasscodeStore(this)) }
    }
}

@Composable
private fun KemzyApp(store: PasscodeStore) {
    var configured by remember { mutableStateOf(store.isConfigured()) }
    var unlocked by remember { mutableStateOf(!configured) }
    var setupCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            if (!unlocked) {
                Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
                    Text("Kémzy àvátâr", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(if (configured) "Enter your private passcode" else "Create your private passcode")
                    Spacer(Modifier.height(18.dp))
                    OutlinedTextField(value = setupCode, onValueChange = { setupCode = it }, label = { Text("Passcode") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        if (!configured) {
                            if (setupCode.length >= 4) { store.setPasscode(setupCode.toCharArray()); configured = true; unlocked = true; setupCode = "" }
                            else message = "Use at least 4 characters."
                        } else if (store.verify(setupCode.toCharArray())) { unlocked = true; setupCode = "" }
                        else message = "Incorrect passcode."
                    }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(if (configured) "Unlock" else "Create passcode") }
                    if (message.isNotEmpty()) Text(message, Modifier.padding(top = 12.dp))
                }
            } else StudioHome()
        }
    }
}

@Composable
private fun StudioHome() {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val engine = remember { LiveAvatarEngine(context) }
    val tracker = remember { FaceTracker() }
    val camera = remember { CameraController(context, lifecycleOwner) }
    var sourceReady by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(ModelDiscovery(context).discover().complete) }
    var status by remember { mutableStateOf(if (modelReady) "Choose a source face" else "Import your existing KemzyModels folder") }
    var liveBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var permissionGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionGranted = it }
    LaunchedEffect(Unit) { if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ModelImporter(context).importFromTree(uri)
            }.onSuccess { result ->
                modelReady = ModelDiscovery(context).discover().complete
                status = if (modelReady) "Models ready — choose a source face" else result.errors.joinToString("; ").ifBlank { "Some required models are missing" }
            }.onFailure { status = "Model import failed: ${it.message ?: "unknown error"}" }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null || !modelReady) return@rememberLauncherForActivityResult
        status = "Preparing source…"
        scope.launch(Dispatchers.Default) {
            val bitmap = runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (bitmap == null) { status = "Unable to read selected image"; return@launch }
            val result = engine.prepare(bitmap)
            bitmap.recycle()
            sourceReady = result.isSuccess
            status = result.exceptionOrNull()?.message ?: "Source ready — live tracking is active"
        }
    }

    LaunchedEffect(sourceReady) {
        if (!sourceReady) return@LaunchedEffect
        while (sourceReady) {
            engine.latestFrame()?.let { liveBitmap = it.bitmap }
            delay(33)
        }
    }

    DisposableEffect(permissionGranted, sourceReady, modelReady) {
        if (permissionGranted && modelReady) {
            val previewView = androidx.camera.view.PreviewView(context)
            previewView.alpha = 0f
            camera.startPreview(previewView) { image ->
                tracker.process(image) { motion: DriverMotion? ->
                    if (motion != null && sourceReady) {
                        scope.launch(Dispatchers.Default) { engine.submit(motion, MotionControls()) }
                    }
                }
            }
        }
        onDispose { camera.stop() }
    }

    DisposableEffect(Unit) { onDispose { camera.close(); tracker.close(); engine.close() } }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Kémzy àvátâr", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(if (sourceReady) "LIVE" else if (modelReady) "READY" else "SETUP", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        if (!modelReady) {
            Text("Android protects shared storage from native model loaders. Select your existing KemzyModels folder once; Kémzy will copy the models into private storage and reuse them on future launches.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { modelPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Import existing KemzyModels") }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
            val frame = liveBitmap
            if (frame != null) Image(frame.asImageBitmap(), contentDescription = "Live Kémzy avatar", modifier = Modifier.fillMaxSize())
            else Text(status, Modifier.padding(24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(when (val s = engine.state) {
            EngineState.Running -> "Live • expressions + head motion"
            is EngineState.Degraded -> s.message
            is EngineState.Error -> s.message
            EngineState.Preparing -> "Preparing source…"
            else -> status
        }, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(10.dp))
        Button(onClick = { picker.launch("image/*") }, enabled = modelReady, modifier = Modifier.fillMaxWidth()) { Text("Select source") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { sourceReady = false; engine.stop(); status = "Choose another source face" }, enabled = sourceReady, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
    }
}
