package com.hesi.slog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFEA1537)

@Composable
fun AuthScreen(authViewModel: AuthViewModel) {
    val ndot = remember { FontFamily(Font(R.font.ndot_regular)) }
    val busy by authViewModel.busy.collectAsState()
    val message by authViewModel.message.collectAsState()

    var isSignUp by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("S  LOG", color = Color.White, fontSize = 44.sp, fontFamily = ndot, letterSpacing = 4.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (isSignUp) "CREATE ACCOUNT" else "LOG IN",
            color = Accent,
            fontSize = 16.sp,
            fontFamily = ndot
        )
        Spacer(Modifier.height(32.dp))

        if (isSignUp) {
            AuthField(name, { name = it }, "Name", ndot)
            Spacer(Modifier.height(12.dp))
        }
        AuthField(email, { email = it.trim() }, "Email", ndot, keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(12.dp))
        AuthField(password, { password = it }, "Password", ndot, isPassword = true)
        if (isSignUp) {
            Spacer(Modifier.height(12.dp))
            AuthField(pin, { pin = it.trim() }, "Registration PIN", ndot, isPassword = true, isPin = true)
            Text(
                "Ask the owner of this S Log for the PIN.",
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message!!, color = Color.LightGray, fontSize = 13.sp, textAlign = TextAlign.Center)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (isSignUp) authViewModel.signUp(name, email, password, pin)
                else authViewModel.signIn(email, password)
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) {
            if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            else Text(if (isSignUp) "SIGN UP" else "LOG IN", fontFamily = ndot, color = Color.White)
        }

        if (!isSignUp) {
            TextButton(onClick = { authViewModel.resetPassword(email) }, enabled = !busy) {
                Text("Forgot password?", color = Color.Gray)
            }
        }
        TextButton(onClick = {
            isSignUp = !isSignUp
            authViewModel.clearMessage()
        }) {
            Text(
                if (isSignUp) "Already have an account? Log in" else "New here? Create an account",
                color = Color.White
            )
        }
    }
}

/** Shown when a signed-in account hasn't entered the registration PIN yet. */
@Composable
fun PinScreen(authViewModel: AuthViewModel, email: String) {
    val ndot = remember { FontFamily(Font(R.font.ndot_regular)) }
    val busy by authViewModel.busy.collectAsState()
    val message by authViewModel.message.collectAsState()
    var pin by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("S  LOG", color = Color.White, fontSize = 44.sp, fontFamily = ndot, letterSpacing = 4.sp)
        Spacer(Modifier.height(8.dp))
        Text("REGISTRATION PIN", color = Accent, fontSize = 16.sp, fontFamily = ndot)
        Spacer(Modifier.height(16.dp))
        Text(
            "$email needs the registration PIN once before it can use S Log.",
            color = Color.LightGray,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        AuthField(pin, { pin = it.trim() }, "Registration PIN", ndot, isPassword = true, isPin = true)
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message!!, color = Color.LightGray, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { authViewModel.submitPin(pin) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) {
            if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            else Text("CONTINUE", fontFamily = ndot, color = Color.White)
        }
        TextButton(onClick = { authViewModel.signOut() }) {
            Text("Use a different account", color = Color.White)
        }
    }
}

/** Short loading screen while the account is checked. */
@Composable
fun CheckingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = Accent)
    }
}

@Composable
private fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    font: FontFamily,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    isPin: Boolean = false
) {
    // password / PIN fields get a SHOW / HIDE switch on the right
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(color = Color.White),
        label = { Text(label, color = Color.LightGray, fontFamily = font) },
        visualTransformation = if (isPassword && !visible) PasswordVisualTransformation()
        else androidx.compose.ui.text.input.VisualTransformation.None,
        trailingIcon = if (isPassword) {
            {
                TextButton(onClick = { visible = !visible }) {
                    Text(
                        if (visible) "HIDE" else "SHOW",
                        color = Accent,
                        fontFamily = font,
                        fontSize = 12.sp
                    )
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                isPin -> KeyboardType.NumberPassword
                isPassword -> KeyboardType.Password
                else -> keyboardType
            }
        ),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent)
    )
}
