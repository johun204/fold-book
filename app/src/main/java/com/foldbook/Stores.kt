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
private inline fun <reified T> loadList(file: File): List<T> {
    if (!file.exists()) return emptyList()
    return runCatching { json.decodeFromString<List<T>>(file.readText()) }.getOrElse {
        // 읽을 수 없는 파일은 빈 목록으로 덮어쓰기 전에 따로 남겨 둔다.
        runCatching { file.copyTo(File(file.path + ".bak"), overwrite = true) }
        emptyList()
    }
}

/** 임시 파일에 쓰고 이름을 바꾼다 — 쓰는 도중 죽어도 기존 목록이 반쪽 파일로 망가지지 않는다. */
private inline fun <reified T> saveList(file: File, list: List<T>) {
    runCatching {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(list))
        if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
    }
}

class ConnectionStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "connections.json")
    // 파일에는 비밀번호를 암호화해 두고(PassCrypt), 메모리에서는 평문으로 쓴다.
    private val stored = loadList<Connection>(file)
    private val _flow = MutableStateFlow(
        seedIfEmpty(stored.map { it.copy(pass = PassCrypt.decrypt(it.pass)) }),
    )

    private fun save() = saveList(file, _flow.value.map { it.copy(pass = PassCrypt.encrypt(it.pass)) })

    init {
        // 예전 버전이 평문으로 저장해 둔 비밀번호는 바로 암호화해서 다시 쓴다.
        if (stored.any { it.pass.isNotEmpty() && !PassCrypt.isEncrypted(it.pass) }) save()
    }
    val flow: StateFlow<List<Connection>> = _flow.asStateFlow()

    private fun seedIfEmpty(list: List<Connection>): List<Connection> {
        if (list.any { it.type == ConnType.LOCAL }) return list
        val local = Connection(id = "local", type = ConnType.LOCAL, label = "기기 저장소")
        return listOf(local) + list     // 파일에는 다음 변경 때 함께 저장된다
    }

    fun get(id: String): Connection? = _flow.value.firstOrNull { it.id == id }

    fun add(c: Connection) {
        _flow.value = _flow.value + c.copy(id = c.id.ifBlank { UUID.randomUUID().toString() })
        save()
    }

    fun remove(id: String) {
        if (id == "local") return
        _flow.value = _flow.value.filterNot { it.id == id }
        save()
    }

    /** 별칭(표시 이름) 변경. */
    fun rename(id: String, label: String) {
        if (id == "local" || label.isBlank()) return
        _flow.value = _flow.value.map { if (it.id == id) it.copy(label = label.trim()) else it }
        save()
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

    fun finishedIds(): List<String> = _flow.value.filter { it.finished }.map { it.id }

    fun clearFinished() {
        _flow.value = _flow.value.filterNot { it.finished }
        saveList(file, _flow.value)
    }

    /** 캐시 스윕 기준: 아직 안 끝난 세션만 유지 대상. */
    fun activeIds(): Set<String> = _flow.value.filterNot { it.finished }.map { it.id }.toSet()
}
