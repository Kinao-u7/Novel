package com.example.data.download

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.data.repository.NovelRepository

object DownloadEngineProvider {

    @Volatile
    private var testRepository: NovelRepository? = null

    fun getRepository(context: Context): NovelRepository {
        return testRepository ?: NovelRepository.getInstance(context)
    }

    @VisibleForTesting
    fun setRepositoryForTesting(repository: NovelRepository?) {
        testRepository = repository
    }
}
