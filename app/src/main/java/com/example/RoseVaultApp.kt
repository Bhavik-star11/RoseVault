package com.example

import android.app.Application
import com.example.cloud.SupabaseCloud
import com.example.data.repository.RoseVaultRepository

class RoseVaultApp : Application() {
    val cloud by lazy { SupabaseCloud(this) }
    val repository by lazy { RoseVaultRepository(this) }
}
