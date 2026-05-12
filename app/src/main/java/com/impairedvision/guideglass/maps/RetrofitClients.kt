package com.impairedvision.guideglass.maps

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

object RetrofitClients {

    // Moshi with Kotlin adapter for data class handling
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    val google: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://maps.googleapis.com/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val directions: DirectionsService by lazy {
        google.create(DirectionsService::class.java)
    }
}
