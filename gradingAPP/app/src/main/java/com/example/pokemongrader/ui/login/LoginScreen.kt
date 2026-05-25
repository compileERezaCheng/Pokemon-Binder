package com.example.pokemongrader.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.pokemongrader.data.DataRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    repository: DataRepository,
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val isLoading by repository.isLoading.collectAsStateWithLifecycle()
    val errorMessage by repository.errorMessage.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize().background(Color(0xFF020617)), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Welcome Trainer", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = { coroutineScope.launch { if(repository.login(email, password)) onLoginSuccess() } }, modifier = Modifier.fillMaxWidth()) {
                Text("LOGIN")
            }
            TextButton(onClick = { coroutineScope.launch { if(repository.register(email, password)) onLoginSuccess() } }) {
                Text("Register Account", color = Color.Gray)
            }
            errorMessage?.let { Text(it, color = Color.Red, fontSize = 12.sp) }
        }
        if (isLoading) CircularProgressIndicator(color = Color.Red)
    }
}
