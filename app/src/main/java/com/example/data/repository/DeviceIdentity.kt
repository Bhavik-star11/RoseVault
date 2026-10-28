package com.example.data.repository

import android.content.Context
import java.util.UUID

fun deviceId(context: Context): String {
    val preferences = context.getSharedPreferences("device-identity", Context.MODE_PRIVATE)
    return preferences.getString("id", null)
        ?: UUID.randomUUID().toString().also { preferences.edit().putString("id", it).apply() }
}
