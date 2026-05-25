package com.example.pokemongrader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.pokemongrader.data.DefaultDataRepository
import com.example.pokemongrader.theme.PokemonGraderTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val repository = DefaultDataRepository(applicationContext)

    enableEdgeToEdge()
    setContent {
      PokemonGraderTheme { 
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { 
          MainNavigation(repository) 
        } 
      }
    }
  }
}

