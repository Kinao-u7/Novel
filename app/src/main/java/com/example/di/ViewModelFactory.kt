package com.example.di

import androidx.compose.runtime.compositionLocalOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.NovelApplication

val LocalAppContainer = compositionLocalOf<AppContainer> {
    error("No AppContainer provided in LocalAppContainer")
}

inline fun <reified VM : ViewModel> appViewModelFactory(
    crossinline creator: (container: AppContainer, savedStateHandle: SavedStateHandle) -> VM
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as? NovelApplication)
            ?: error("Application is not NovelApplication")
        val savedStateHandle = createSavedStateHandle()
        creator(app.container, savedStateHandle)
    }
}
