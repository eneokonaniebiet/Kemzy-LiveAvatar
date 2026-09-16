package com.kemzy.liveavatar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kemzy.liveavatar.security.PasscodeStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = PasscodeStore(this)
        setContent { KemzyShell(store) }
    }
}

@Composable
private fun KemzyShell(store: PasscodeStore) {
    var unlocked by remember { mutableStateOf(!store.isConfigured()) }
    var setup by remember { mutableStateOf(!store.isConfigured()) }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            if (unlocked) {
                StudioHome()
            } else {
                Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement=Arrangement.Center) {
                    Text("Kémzy àvátâr", style=MaterialTheme.typography.headlineLarge)
                    Text(if(setup) "Create your private passcode" else "Enter your passcode", modifier=Modifier.padding(top=8.dp,bottom=20.dp))
                    OutlinedTextField(value=code,onValueChange={code=it},label={Text("Passcode")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                    Button(onClick={if(setup){if(code.length>=4){store.setPasscode(code.toCharArray());setup=false;code="";message="Passcode saved"}else message="Use at least 4 characters"}else{if(store.verify(code.toCharArray())){unlocked=true;code=""}else message="Incorrect passcode"}},modifier=Modifier.fillMaxWidth().padding(top=16.dp)){Text(if(setup) "Create passcode" else "Unlock")}
                    if(message.isNotEmpty()) Text(message,modifier=Modifier.padding(top=12.dp))
                }
            }
        }
    }
}

@Composable
private fun StudioHome() {
    Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text("Kémzy àvátâr",style=MaterialTheme.typography.headlineLarge)
        Text("Xpression-style Live Studio",style=MaterialTheme.typography.titleMedium)
        Text("Select a source face, enable camera tracking, then animate it with real-time expressions and head movement.")
        Button(onClick={},modifier=Modifier.fillMaxWidth()){Text("Select source")}
        Button(onClick={},modifier=Modifier.fillMaxWidth()){Text("Open Live Studio")}
        Button(onClick={},modifier=Modifier.fillMaxWidth()){Text("Avatar library")}
        Button(onClick={},modifier=Modifier.fillMaxWidth()){Text("Diagnostics")}
    }
}
