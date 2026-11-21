package com.bestwinesport.staff

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

/**
 * Staff login: LOGIN ONLY. There is no signup / create-account here and staff accounts are accepted only
 * if their role is "staff". Staff are created by an admin in Staff Management.
 */
@Composable
fun StaffLogin(nav: NavController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf("") }; var pw by remember { mutableStateOf("") }; var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        Spacer(Modifier.height(30.dp))
        Text("Staff Login", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Use the email/username, password and 12-digit key your admin gave you", color = Color.Gray, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(id, { id = it }, Modifier.fillMaxWidth(), label = { Text("Email or username") }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(key, { key = it.filter(Char::isDigit).take(12) }, Modifier.fillMaxWidth(), label = { Text("12-digit login key") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
        Spacer(Modifier.height(20.dp))
        ABtn("LOGIN", Modifier.fillMaxWidth(), Green) {
            if (busy) return@ABtn
            if (id.isBlank() || pw.isBlank()) { ctx.toast("Fill all fields"); return@ABtn }
            if (key.length != 12) { ctx.toast("Login key must be 12 digits"); return@ABtn }
            busy = true
            scope.launch {
                val e = AR.login(id, pw, key, "staff"); busy = false
                if (e == null) nav.navigate("home") { popUpTo("login") { inclusive = true } } else ctx.toast(e)
            }
        }
    }
}
