package com.pwde.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pwde.app.PwdeApplication
import com.pwde.app.di.AppContainer

/** Builds a ViewModel from the [AppContainer], scoped to the current NavBackStackEntry/Activity. */
@Composable
inline fun <reified VM : ViewModel> pwdeViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM = viewModel(
    key = key,
    factory = viewModelFactory {
        initializer { create((this[APPLICATION_KEY] as PwdeApplication).container) }
    },
)

/** The app's singletons, for the few composables that drive one directly (the overlay claim). */
@Composable
fun pwdeContainer(): AppContainer =
    (LocalContext.current.applicationContext as PwdeApplication).container
