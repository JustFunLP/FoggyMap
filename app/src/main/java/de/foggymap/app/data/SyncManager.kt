package de.foggymap.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import de.foggymap.app.App
import de.foggymap.app.BuildConfig
import de.foggymap.app.core.Invite
import de.foggymap.app.core.LatLon
import de.foggymap.app.core.RevealItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GroupMember(val key: String, val name: String, val roads: Int, val km: Double, val lastSeen: Long, val live: LatLon?, val isMe: Boolean)

data class SyncUi(
    val server: String = "",
    val groupCode: String = "",
    val groupName: String = "",
    val status: Status = Status.NOT_CONFIGURED,
    val message: String = "",
    val lastSync: Long = 0,
    val members: List<GroupMember> = emptyList(),
    val groupRoads: Int = 0,
    val groupKm: Double = 0.0,
    val pendingUploads: Int = 0,
) {
    enum class Status { NOT_CONFIGURED, IDLE, SYNCING, OK, OFFLINE, ERROR }
    val inGroup get() = groupCode.isNotEmpty()
}

/**
 * Group synchronisation over HTTPS. Offline-first: reveals are stored locally with synced = 0
 * and uploaded whenever the server is reachable; downloads are incremental by server revision.
 */
class SyncManager(private val context: Context, private val db: RevealDatabase, private val prefs: Prefs) {
    val state = MutableStateFlow(SyncUi())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private var scheduled: Job? = null
    private val json = "application/json; charset=utf-8".toMediaType()

    val server: String get() = prefs.server.ifEmpty { BuildConfig.DEFAULT_SERVER }
    val configured get() = Invite.isValidServer(server) && prefs.groupCode.isNotEmpty()

    fun init() {
        refreshState()
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { requestSync(3_000) }
            })
        } catch (_: Exception) { }
    }

    fun refreshState() {
        state.update {
            it.copy(server = server, groupCode = prefs.groupCode, groupName = prefs.groupName,
                status = if (!configured) SyncUi.Status.NOT_CONFIGURED else if (it.status == SyncUi.Status.NOT_CONFIGURED) SyncUi.Status.IDLE else it.status,
                lastSync = prefs.lastSyncMs, members = if (prefs.groupCode.isEmpty()) emptyList() else it.members)
        }
    }

    /** Debounced sync – many reveals in a row cause one upload. */
    fun requestSync(delayMs: Long = 20_000) {
        if (!configured) return
        scheduled?.cancel()
        scheduled = App.scope.launch { delay(delayMs); syncNow() }
    }

    suspend fun syncNow(): Boolean = withContext(Dispatchers.IO) {
        if (!configured) return@withContext false
        mutex.withLock {
            state.update { it.copy(status = SyncUi.Status.SYNCING, pendingUploads = db.unsyncedCount()) }
            try {
                val base = "$server/v2/groups/${prefs.groupCode}"
                // 1) upload the outbox
                var rounds = 0
                while (rounds++ < 40) {
                    val batch = db.unsynced(150)
                    if (batch.isEmpty()) break
                    val body = JSONObject().put("member", member()).put("items", JSONArray().apply { batch.forEach { put(it.toJson()) } })
                    val rsp = call(Request.Builder().url("$base/reveals").post(body.toString().toRequestBody(json)).build())
                    when (rsp.first) {
                        200 -> { db.markSynced(batch.map { it.id }); rsp.second?.optString("member")?.takeIf { it.isNotEmpty() }?.let { prefs.memberKey = it } }
                        400 -> { db.markSynced(batch.map { it.id }) }  // never block the queue on one bad element
                        404 -> throw SyncError("Die Gruppe existiert auf dem Server nicht mehr.")
                        else -> throw SyncError(rsp.second?.optString("error")?.ifEmpty { null } ?: "Server antwortet ${rsp.first}")
                    }
                }
                // 2) download everything new
                var since = prefs.syncRevision
                var pages = 0
                val newRoads = ArrayList<String>()
                var anyNew = false
                while (pages++ < 200) {
                    val rsp = call(Request.Builder().url("$base/reveals?since=$since&limit=500").get().build())
                    if (rsp.first != 200) throw SyncError("Download fehlgeschlagen (${rsp.first})")
                    val o = rsp.second ?: break
                    val arr = o.getJSONArray("items")
                    val byOwner = HashMap<String, MutableList<RevealItem>>()
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        val item = RevealItem.fromJson(e) ?: continue
                        byOwner.getOrPut(e.optString("by", "group")) { ArrayList() }.add(item)
                    }
                    for ((owner, items) in byOwner) {
                        val ownerName = if (owner == prefs.memberKey) RevealDatabase.OWNER_ME else owner
                        val added = db.insert(items, ownerName, synced = true)
                        if (added.isNotEmpty()) anyNew = true
                        added.filter { it.id.startsWith("r:") }.forEach { newRoads += it.id }
                    }
                    since = o.getLong("revision")
                    prefs.syncRevision = since
                    if (!o.optBoolean("more")) break
                }
                if (anyNew) App.notifyDbChanged(remoteRoads = newRoads)
                // 3) group overview
                val g = call(Request.Builder().url(base).get().build())
                if (g.first == 200 && g.second != null) applyGroup(g.second!!)
                prefs.lastSyncMs = System.currentTimeMillis()
                state.update { it.copy(status = SyncUi.Status.OK, message = "", lastSync = prefs.lastSyncMs, pendingUploads = db.unsyncedCount()) }
                true
            } catch (e: SyncError) {
                state.update { it.copy(status = SyncUi.Status.ERROR, message = e.message ?: "Fehler", pendingUploads = db.unsyncedCount()) }
                false
            } catch (e: IOException) {
                state.update { it.copy(status = SyncUi.Status.OFFLINE, message = "Offline – wird automatisch nachgeholt", pendingUploads = db.unsyncedCount()) }
                false
            } catch (e: Exception) {
                state.update { it.copy(status = SyncUi.Status.ERROR, message = e.message ?: e.javaClass.simpleName) }
                false
            }
        }
    }

    private fun applyGroup(o: JSONObject) {
        val name = o.optString("name")
        if (name.isNotEmpty()) prefs.groupName = name
        val arr = o.optJSONArray("members") ?: JSONArray()
        val me = prefs.memberKey
        val members = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            val live = m.optJSONObject("live")?.let { LatLon(it.getDouble("lat"), it.getDouble("lon")) }
            GroupMember(m.getString("key"), m.optString("name"), m.optInt("roads"), m.optDouble("km", 0.0),
                m.optLong("lastSeen"), live, m.getString("key") == me)
        }
        state.update { it.copy(groupName = prefs.groupName, members = members, groupRoads = o.optInt("roads"), groupKm = o.optDouble("km", 0.0)) }
    }

    suspend fun createGroup(name: String, serverUrl: String?): Result<String> = withContext(Dispatchers.IO) {
        try {
            serverUrl?.let { if (it.isNotBlank()) prefs.server = it }
            if (!Invite.isValidServer(server)) return@withContext Result.failure(SyncError("Bitte eine gültige https://-Serveradresse eintragen."))
            val body = JSONObject().put("name", name.trim().ifEmpty { "FoggyMap-Gruppe" })
            val rsp = call(Request.Builder().url("$server/v2/groups").post(body.toString().toRequestBody(json)).build())
            if (rsp.first != 201) return@withContext Result.failure(SyncError(rsp.second?.optString("error") ?: "Server antwortet ${rsp.first}"))
            val code = rsp.second!!.getString("code")
            enterGroup(code, rsp.second!!.optString("name", name))
            syncNow()
            Result.success(code)
        } catch (e: IOException) { Result.failure(SyncError("Server nicht erreichbar: ${e.message}")) }
    }

    suspend fun joinGroup(input: String, serverUrl: String?): Result<String> = withContext(Dispatchers.IO) {
        val parsed = Invite.parse(input) ?: return@withContext Result.failure(SyncError("Kein gültiger Gruppencode (12 Zeichen, z. B. A7K9-P3QX-M2TF)."))
        val target = (parsed.server ?: serverUrl?.takeIf { it.isNotBlank() } ?: server).trimEnd('/')
        if (!Invite.isValidServer(target)) return@withContext Result.failure(SyncError("Keine gültige https://-Serveradresse. Füge die komplette Einladung ein."))
        try {
            val rsp = call(Request.Builder().url("$target/v2/groups/${parsed.code}").get().build())
            when (rsp.first) {
                200 -> {
                    if (target != server) prefs.server = target
                    enterGroup(parsed.code, rsp.second?.optString("name") ?: "")
                    rsp.second?.let(::applyGroup)
                    syncNow()
                    Result.success(parsed.code)
                }
                404 -> Result.failure(SyncError("Diese Gruppe gibt es auf dem Server nicht. Code prüfen."))
                429 -> Result.failure(SyncError("Zu viele Versuche – bitte später erneut."))
                else -> Result.failure(SyncError("Server antwortet ${rsp.first}"))
            }
        } catch (e: IOException) { Result.failure(SyncError("Server nicht erreichbar: ${e.message}")) }
    }

    private fun enterGroup(code: String, name: String) {
        val changed = code != prefs.groupCode
        prefs.groupCode = code
        prefs.groupName = name
        if (changed) {
            prefs.syncRevision = 0
            db.markAllUnsynced()        // bring own progress into the new group
        }
        refreshState()
    }

    fun leaveGroup() {
        val wasSharing = prefs.shareLiveLocation
        if (wasSharing) App.scope.launch { clearLive() }
        prefs.groupCode = ""; prefs.groupName = ""; prefs.syncRevision = 0; prefs.shareLiveLocation = false
        state.value = SyncUi(server = server)
        refreshState()
    }

    suspend fun pushLive(lat: Double, lon: Double) = withContext(Dispatchers.IO) {
        if (!configured || !prefs.shareLiveLocation) return@withContext
        try {
            val body = JSONObject().put("member", member()).put("lat", lat).put("lon", lon)
            call(Request.Builder().url("$server/v2/groups/${prefs.groupCode}/live").put(body.toString().toRequestBody(json)).build())
        } catch (_: IOException) { }
    }

    suspend fun clearLive() = withContext(Dispatchers.IO) {
        if (!Invite.isValidServer(server) || prefs.groupCode.isEmpty()) return@withContext
        try { call(Request.Builder().url("$server/v2/groups/${prefs.groupCode}/live?member=${prefs.memberId}").delete().build()) } catch (_: IOException) { }
    }

    private fun member() = JSONObject().put("id", prefs.memberId).put("name", prefs.nickname)

    private fun call(req: Request): Pair<Int, JSONObject?> {
        client.newCall(req.newBuilder().header("User-Agent", "FoggyMap/${BuildConfig.VERSION_NAME}").build()).execute().use { rsp ->
            val text = rsp.body?.string().orEmpty()
            val obj = try { if (text.isNotEmpty()) JSONObject(text) else null } catch (_: Exception) { null }
            return rsp.code to obj
        }
    }

    class SyncError(msg: String) : Exception(msg)
}
