package com.ensemblereads.app.data

import com.ensemblereads.app.data.db.SettingsDao
import com.ensemblereads.app.data.db.SettingsEntity
import kotlinx.coroutines.flow.Flow

class SettingsManager(private val dao: SettingsDao) {
    companion object {
        const val KEY_DEEPSEEK_KEY = "deepseek_api_key"
        const val KEY_DEFAULT_SPEED = "default_speed"
        const val KEY_CACHE_LIMIT = "cache_limit"
        const val DEFAULT_CACHE_LIMIT = "100"
    }
    suspend fun get(key: String): String? {
        val raw = dao.get(key)?.value ?: return null
        // DeepSeek key 在库里是密文；解密失败（如旧版明文）则原样返回
        return if (key == KEY_DEEPSEEK_KEY) KeyStoreCipher.decrypt(raw) ?: raw else raw
    }

    suspend fun put(key: String, value: String) {
        val stored = if (key == KEY_DEEPSEEK_KEY) KeyStoreCipher.encrypt(value) ?: value else value
        dao.put(SettingsEntity(key, stored))
    }
    /** 可观察的完整设置表：保存后 UI 即时感知，无需重启。 */
    fun all(): Flow<List<SettingsEntity>> = dao.allFlow()
}
