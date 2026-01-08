package cn.bincker.stream.sound.repository

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class RepositoryModel {
    @Provides
    @Singleton
    fun appConfigRepository(@ApplicationContext context: Context): AppConfigRepository = runBlocking {
        AppConfigRepository.newInstance(context)
    }
}
