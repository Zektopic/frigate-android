package com.zektopic.frigate.di

import android.content.Context
import com.zektopic.frigate.data.NvrDao
import com.zektopic.frigate.data.NvrDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NvrDatabase {
        return NvrDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideNvrDao(database: NvrDatabase): NvrDao {
        return database.nvrDao()
    }
}
