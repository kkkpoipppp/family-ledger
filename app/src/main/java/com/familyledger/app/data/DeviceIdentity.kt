package com.familyledger.app.data

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

class DeviceIdentity(context: Context) {
    private val preferences = context.getSharedPreferences("ledger_identity", Context.MODE_PRIVATE)

    val id: String = preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
        preferences.edit { putString(KEY_DEVICE_ID, it) }
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
    }
}
