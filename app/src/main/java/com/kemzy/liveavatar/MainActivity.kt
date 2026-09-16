package com.kemzy.liveavatar

import android.Manifest
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
import androidx.compose.foundation.layout.Row
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kemzy.liveavatar.camera.CameraController
import com.kemzy.liveavatar.camera.FaceTracker
import com.kemzy.liveavatar.engine.EngineState
import com.kemzy.liveavatar.engine.LiveAvatarEngine
import com.kemzy.liveavatar.engine.MotionControls
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
    var status by remember { mutableStateOf("Choose a source face") }
    var liveBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var permissionGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionGranted = it }
    LaunchedEffect(Unit) { if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
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

    DisposableEffect(permissionGranted) {
        if (permissionGranted) {
            val previewView = androidx.camera.view.PreviewView(context)
            previewView.alpha = 0f
            camera.startPreview(previewView) { image ->
                tracker.process(image) { motion ->
                    if (motion != null && sourceReady) scope.launch(Dispatchers.Default) { engine.submit(motion, MotionControls()) }
                }
            }
        }
        onDispose { camera.stop() }
    }

    DisposableEffect(Unit) { onDispose { camera.close(); engine.close() } }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Kémzy àvátâr", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Text(if (sourceReady) "LIVE" else "SETUP", style = MaterialTheme.typography.labelLarge)
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
            EngineState.Preparing -> "AI preparing source…"
            else -> status
        }, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { picker.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Select source") }
            OutlinedButton(onClick = { sourceReady = false; engine.stop(); status = "Choose another source face" }, modifier = Modifier.weight(1f)) { Text("Stop") }
        }
    }
}
