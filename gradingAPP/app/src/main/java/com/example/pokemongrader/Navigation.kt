package com.example.pokemongrader

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.pokemongrader.data.DataRepository
import com.example.pokemongrader.ui.login.LoginScreen
import com.example.pokemongrader.ui.main.MainScreen
import com.example.pokemongrader.ui.scan.ScanScreen
import com.example.pokemongrader.ui.details.CardDetailsScreen
import com.example.pokemongrader.ui.account.AccountSettingsScreen
import com.example.pokemongrader.ui.account.ProfileEditScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue

@Composable
fun MainNavigation(repository: DataRepository) {
  val isLoggedIn by repository.isLoggedIn.collectAsStateWithLifecycle()
  val initialKey = if (isLoggedIn) Main else Login
  val backStack = rememberNavBackStack(initialKey)

  NavDisplay(
      backStack = backStack,
      onBack = { backStack.removeLastOrNull() },
      entryProvider = entryProvider {
        entry<Login> {
          LoginScreen(
            repository = repository,
            onLoginSuccess = { backStack.replaceAll { Main } },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Main> {
          MainScreen(
            repository = repository,
            onNavigateToScan = { backStack.add(Scan) },
            onNavigateToCardDetails = { page, slot, date -> backStack.add(CardDetails(page, slot, date)) },
            onNavigateToAccountSettings = { backStack.add(AccountSettings) },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Scan> {
          ScanScreen(
            repository = repository,
            onNavigateBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<CardDetails> { key ->
          CardDetailsScreen(
            page = key.page,
            slot = key.slot,
            dateAdded = key.dateAdded,
            repository = repository,
            onNavigateBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<AccountSettings> {
          AccountSettingsScreen(
            repository = repository,
            onNavigateToProfileEdit = { backStack.add(ProfileEdit) },
            onNavigateBack = { backStack.removeLastOrNull() },
            onLogout = {
                repository.logout()
                backStack.replaceAll { Login }
            },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<ProfileEdit> {
          ProfileEditScreen(
            repository = repository,
            onNavigateBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
      },
  )
}
