package com.example

import android.app.Application
import com.example.cloud.SupabaseCloud
import com.example.data.repository.RoseVaultRepository

class RoseVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        org.matrix.rustsdk.Android.init(this)
    }

    val cloud by lazy { SupabaseCloud(this) }
    val repository by lazy { RoseVaultRepository(this) }
}
