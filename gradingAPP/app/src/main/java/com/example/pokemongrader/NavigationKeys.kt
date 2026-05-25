package com.example.pokemongrader

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Login : NavKey
@Serializable data object Main : NavKey
@Serializable data object Scan : NavKey
@Serializable data object AccountSettings : NavKey
@Serializable data object ProfileEdit : NavKey
@Serializable data class CardDetails(val page: Int, val slot: Int, val dateAdded: String) : NavKey
