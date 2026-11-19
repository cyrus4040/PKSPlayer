package com.poros.player

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

fun bioAvailable(ctx: Context): Boolean =
    BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
        BiometricManager.BIOMETRIC_SUCCESS

fun promptBiometric(activity: FragmentActivity, onSuccess: () -> Unit) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
        }
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("PK'S Player")
        .setSubtitle("Verify it's you")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        .setNegativeButtonText("Use PIN")
        .build()
    prompt.authenticate(info)
}

@Composable
fun LockScreen(title: String, onUnlocked: () -> Unit, onCancel: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val st = remember { Settings.get(ctx) }
    val activity = ctx as? FragmentActivity
    val canBio = st.bio && activity != null && bioAvailable(ctx)
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (canBio) promptBiometric(activity!!, onUnlocked) }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) { pin = it; error = false } },
            label = { Text(if (error) "Wrong PIN" else "PIN") },
            isError = error,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { if (st.checkPin(pin)) onUnlocked() else { error = true; pin = "" } }) { Text("Unlock") }
        if (canBio) TextButton(onClick = { promptBiometric(activity!!, onUnlocked) }) { Text("Use fingerprint") }
        if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
fun SetPinPanel(title: String, onDone: (String) -> Unit, onCancel: (() -> Unit)? = null) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val ok = a.length >= 4 && a == b
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        val ko = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
        OutlinedTextField(a, { if (it.length <= 8 && it.all(Char::isDigit)) a = it },
            label = { Text("New PIN (4-8 digits)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = ko)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(b, { if (it.length <= 8 && it.all(Char::isDigit)) b = it },
            label = { Text("Confirm PIN") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = ko)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onDone(a) }, enabled = ok) { Text("Save PIN") }
        if (onCancel != null) TextButton(onClick = onCancel) { Text("Cancel") }
    }
}
