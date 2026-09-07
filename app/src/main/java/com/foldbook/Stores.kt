package com.foldbook

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

/** filesDir 안 JSON 파일 1개에 리스트를 저장하는 초경량 스토어. 목록이 작아 통짜 저장이면 충분. */
private inline fun <reified T> loadList(file: File): List<T> =
    runCatching { if (file.exists()) json.decodeFromString<List<T>>(file.readText()) else emptyList() }
        .getOrDefault(emptyList())

private inline fun <reified T> saveList(file: File, list: List<T>) {
    runCatching { file.writeText(json.encodeToString(list)) }
}

class ConnectionStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "connections.json")
    private val _flow = MutableStateFlow(seedIfEmpty(loadList<Connection>(file)))
    val flow: StateFlow<List<Connection>> = _flow.asStateFlow()

    private fun seedIfEmpty(list: List<Connection>): List<Connection> {
        if (list.any { it.type == ConnType.LOCAL }) return list
        val local = Connection(id = "local", type = ConnType.LOCAL, label = "기기 저장소")
        val next = listOf(local) + list
        saveList(file, next)
        return next
    }

    fun get(id: String): Connection? = _flow.value.firstOrNull { it.id == id }

    fun add(c: Connection) {
        _flow.value = _flow.value + c.copy(id = c.id.ifBlank { UUID.randomUUID().toString() })
        saveList(file, _flow.value)
    }

    fun remove(id: String) {
        if (id == "local") return
        _flow.value = _flow.value.filterNot { it.id == id }
        saveList(file, _flow.value)
    }
}

class SessionStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "sessions.json")
    private val _flow = MutableStateFlow(loadList<Session>(file).sortedByDescending { it.updatedAt })
    val flow: StateFlow<List<Session>> = _flow.asStateFlow()

    fun get(id: String): Session? = _flow.value.firstOrNull { it.id == id }

    /** 연결+폴더로 기존 세션을 찾거나 새로 만든다. */
    fun findOrCreate(conn: Connection, folderId: String, folderName: String): Session {
        _flow.value.firstOrNull { it.connectionId == conn.id && it.folderId == folderId }?.let { return it }
        val s = Session(
            id = UUID.randomUUID().toString(),
            connectionId = conn.id,
            connectionLabel = conn.label,
            connType = conn.type,
            folderId = folderId,
            folderName = folderName,
            updatedAt = System.currentTimeMillis(),
        )
        upsert(s)
        return s
    }

    fun upsert(s: Session) {
        val updated = s.copy(updatedAt = System.currentTimeMillis())
        _flow.value = (_flow.value.filterNot { it.id == s.id } + updated)
            .sortedByDescending { it.updatedAt }
        saveList(file, _flow.value)
    }

    fun remove(id: String) {
        _flow.value = _flow.value.filterNot { it.id == id }
        saveList(file, _flow.value)
    }

    fun activeIds(): Set<String> = _flow.value.map { it.id }.toSet()
}
