package com.example.hiltible.di

import android.content.Context
import com.example.hiltible.ble.BleTagDetector
import com.example.hiltible.ble.anchor.AnchorSelector
import com.example.hiltible.ble.anchor.AnchorSelectionRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ToolsReference

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SightingsReference

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideFirebaseDatabase(): FirebaseDatabase {
        return FirebaseDatabase.getInstance()
    }

    @Provides
    @Singleton
    @ToolsReference
    fun provideToolsReference(database: FirebaseDatabase): DatabaseReference {
        return database.getReference("ble-localization-poc/tools")
    }

    @Provides
    @Singleton
    @SightingsReference
    fun provideSightingsReference(database: FirebaseDatabase): DatabaseReference {
        return database.getReference("ble-localization-poc/sightings")
    }

    @Provides
    @Singleton
    fun provideFusedLocationClient(
        @ApplicationContext context: Context
    ): FusedLocationProviderClient {
        return LocationServices.getFusedLocationProviderClient(context)
    }

    @Provides
    @Singleton
    fun provideAnchorSelector(): AnchorSelector {
        return AnchorSelector(
            activeWindowMs = 10_000L,
            hysteresisDdBm = 5,
            consecutiveThreshold = 3,
            maxAnchorHistorySize = 10
        )
    }

    @Provides
    @Singleton
    fun provideAnchorSelectionRepository(
        @ToolsReference toolsRef: DatabaseReference
    ): AnchorSelectionRepository {
        return AnchorSelectionRepository(toolsRef)
    }

    @Provides
    @Singleton
    fun provideBleTagDetector(
        @ApplicationContext context: Context,
        @ToolsReference toolsRef: DatabaseReference,
        @SightingsReference sightingsRef: DatabaseReference,
        fusedLocationClient: FusedLocationProviderClient,
        anchorSelector: AnchorSelector,
        anchorSelectionRepository: AnchorSelectionRepository
    ): BleTagDetector {
        return BleTagDetector(context, toolsRef, sightingsRef, fusedLocationClient, anchorSelector, anchorSelectionRepository)
    }
}

