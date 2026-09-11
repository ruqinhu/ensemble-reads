package com.ensemblereads.app.data

import com.ensemblereads.app.data.db.SettingsDao
import com.ensemblereads.app.data.db.SettingsEntity

class SettingsManager(private val dao: SettingsDao) {
    companion object {
        const val KEY_DEEPSEEK_KEY = "deepseek_api_key"
        const val KEY_DEFAULT_SPEED = "default_speed"
        const val KEY_CACHE_LIMIT = "cache_limit"
        const val DEFAULT_CACHE_LIMIT = "100"
    }
    suspend fun get(key: String): String? = dao.get(key)?.value
    suspend fun put(key: String, value: String) = dao.put(SettingsEntity(key, value))
}
