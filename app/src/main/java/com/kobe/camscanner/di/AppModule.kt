package com.kobe.camscanner.di

import android.content.Context
import androidx.room.Room
import com.kobe.camscanner.core.common.ApplicationScope
import com.kobe.camscanner.core.common.DefaultDispatcher
import com.kobe.camscanner.core.common.IoDispatcher
import com.kobe.camscanner.data.local.DocumentDao
import com.kobe.camscanner.data.local.FolderDao
import com.kobe.camscanner.data.local.KobeDatabase
import com.kobe.camscanner.data.local.PageDao
import com.kobe.camscanner.data.local.SearchDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KobeDatabase =
        Room.databaseBuilder(context, KobeDatabase::class.java, KobeDatabase.NAME)
            // The library is a local index over files that remain the source of truth, so a
            // schema change may rebuild it rather than block the app behind a migration.
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideDocumentDao(db: KobeDatabase): DocumentDao = db.documentDao()
    @Provides fun providePageDao(db: KobeDatabase): PageDao = db.pageDao()
    @Provides fun provideFolderDao(db: KobeDatabase): FolderDao = db.folderDao()
    @Provides fun provideSearchDao(db: KobeDatabase): SearchDao = db.searchDao()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Image processing is CPU-bound. Keeping it off the IO pool means a long enhancement pass
     * cannot starve the file writes happening alongside it.
     */
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@IoDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}
