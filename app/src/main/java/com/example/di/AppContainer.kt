package com.example.di

import android.content.Context
import com.example.data.repository.NovelRepository
import com.example.data.repository.SettingsRepository

/**
 * Dependency container for application-scoped singletons.
 *
 * Temporary adapter note:
 * Owns [novelRepository] by wrapping [NovelRepository.getInstance].
 * UI components and ViewModels obtain the repository ONLY via this container.
 * This adapter will be deleted in Stage 2b when repositories are decoupled from singletons
 * and wired directly into dedicated ViewModels.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext ?: context

    val novelRepository: NovelRepository by lazy {
        NovelRepository.getInstance(appContext)
    }

    val settingsRepository: SettingsRepository
        get() = novelRepository.settingsRepository
}
