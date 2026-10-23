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

    /** Own https server if one is set, otherwise the public Nostr relays (no setup needed). */
    val server: String get() = prefs.server.ifEmpty { BuildConfig.DEFAULT_SERVER }.ifEmpty { Invite.NOSTR }
    val usesNostr get() = server == Invite.NOSTR
    val configured get() = prefs.groupCode.isNotEmpty() && (usesNostr || validServer(server))

    private val relays by lazy { NostrRelays(NostrRelays.DEFAULT, "FoggyMap/${BuildConfig.VERSION_NAME} (Android; https://github.com/JustFunLP/FoggyMap)") }
    @Volatile private var nostrGroup: NostrGroupSync? = null
    /** The group key derivation is deliberately slow (PBKDF2) – keep it per group. */
    /** Live channel of the current group (only with the relays, not with an own server). */
    fun liveChannel(): NostrLive? {
        if (!usesNostr || prefs.groupCode.isEmpty()) return null
        return NostrLive(relays.urls, "FoggyMap/${BuildConfig.VERSION_NAME} (Android; https://github.com/JustFunLP/FoggyMap)",
            prefs.nostrSecret(), nostr().key)
    }

    private fun nostr(code: String = prefs.groupCode): NostrGroupSync =
        nostrGroup?.takeIf { it.code == code } ?: NostrGroupSync(relays, prefs.nostrSecret(), code).also { if (code == prefs.groupCode) nostrGroup = it }

    /** HTTPS only. Debug builds additionally accept the emulator host (automated end-to-end test). */
    private fun validServer(url: String) = Invite.isValidServer(url) ||
        (BuildConfig.DEBUG && Regex("^http://10\\.0\\.2\\.2:[0-9]{2,5}$").matches(url))

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
        // Relays: bundle more (every sync re-sends the newest package to several relays).
        val wait = if (usesNostr && delayMs >= 20_000) 120_000 else delayMs
        scheduled?.cancel()
        scheduled = App.scope.launch { delay(wait); syncNow() }
    }

    suspend fun syncNow(): Boolean = withContext(Dispatchers.IO) {
        if (!configured) return@withContext false
        if (usesNostr) return@withContext mutex.withLock { syncNostr() }
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
                android.util.Log.i("FoggyMap", "sync ok: revision $since, ${db.unsyncedCount()} waiting for upload")
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
        if (serverUrl.isNullOrBlank() && usesNostr) {
            // No server needed: the code is made here, the group lives on the relays.
            val code = Invite.newCode()
            enterGroup(code, name.trim().ifEmpty { "FoggyMap-Gruppe" })
            android.util.Log.i("FoggyMap", "group created $code (nostr)")
            syncNow()
            return@withContext Result.success(code)
        }
        try {
            serverUrl?.let { if (it.isNotBlank()) prefs.server = it }
            if (!validServer(server)) return@withContext Result.failure(SyncError("Bitte eine gültige https://-Serveradresse eintragen."))
            val body = JSONObject().put("name", name.trim().ifEmpty { "FoggyMap-Gruppe" })
            val rsp = call(Request.Builder().url("$server/v2/groups").post(body.toString().toRequestBody(json)).build())
            if (rsp.first != 201) return@withContext Result.failure(SyncError(rsp.second?.optString("error") ?: "Server antwortet ${rsp.first}"))
            val code = rsp.second!!.getString("code")
            android.util.Log.i("FoggyMap", "group created $code")
            enterGroup(code, rsp.second!!.optString("name", name))
            syncNow()
            Result.success(code)
        } catch (e: IOException) { Result.failure(SyncError("Server nicht erreichbar: ${e.message}")) }
    }

    suspend fun joinGroup(input: String, serverUrl: String?): Result<String> = withContext(Dispatchers.IO) {
        val parsed = Invite.parse(input) ?: return@withContext Result.failure(SyncError("Kein gültiger Gruppencode (12 Zeichen, z. B. A7K9-P3QX-M2TF)."))
        if (parsed.server == Invite.NOSTR || (parsed.server == null && serverUrl.isNullOrBlank() && usesNostr)) {
            val found = try { nostr(parsed.code).findGroup() } catch (e: IOException) {
                return@withContext Result.failure(SyncError("Keine Internetverbindung zu den Koop-Relays. Bitte später erneut versuchen."))
            } ?: return@withContext Result.failure(SyncError("Keine Gruppe mit diesem Code gefunden. Bitte den Code prüfen – " +
                "der Ersteller muss die App nach dem Erstellen einmal mit Internet geöffnet haben."))
            if (prefs.server.isNotEmpty() || BuildConfig.DEFAULT_SERVER.isNotEmpty()) prefs.server = Invite.NOSTR
            enterGroup(parsed.code, found.groupName.ifEmpty { "FoggyMap-Gruppe" })
            syncNow()
            return@withContext Result.success(parsed.code)
        }
        val target = (parsed.server ?: serverUrl?.takeIf { it.isNotBlank() } ?: server).trimEnd('/')
        if (!validServer(target)) return@withContext Result.failure(SyncError("Keine gültige https://-Serveradresse. Füge die komplette Einladung ein."))
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
            prefs.nostrSince = 0; prefs.nostrPublished = HashMap(); prefs.nostrMetaHash = ""
            nostrGroup = null
            db.markAllUnsynced()        // bring own progress into the new group
        }
        refreshState()
        App.live.refresh()
    }

    fun leaveGroup() {
        val wasSharing = prefs.shareLiveLocation
        if (wasSharing) App.scope.launch { clearLive() }
        prefs.groupCode = ""; prefs.groupName = ""; prefs.syncRevision = 0; prefs.shareLiveLocation = false
        prefs.nostrSince = 0; prefs.nostrPublished = HashMap(); prefs.nostrMetaHash = ""
        state.value = SyncUi(server = server)
        refreshState()
        App.live.refresh()
    }

    suspend fun pushLive(lat: Double, lon: Double) = withContext(Dispatchers.IO) {
        if (!configured || !prefs.shareLiveLocation) return@withContext
        if (usesNostr) { runCatching { nostr().pushLive(lat, lon, true) }; return@withContext }
        try {
            val body = JSONObject().put("member", member()).put("lat", lat).put("lon", lon)
            call(Request.Builder().url("$server/v2/groups/${prefs.groupCode}/live").put(body.toString().toRequestBody(json)).build())
        } catch (_: IOException) { }
    }

    suspend fun clearLive() = withContext(Dispatchers.IO) {
        if (usesNostr && prefs.groupCode.isNotEmpty()) { runCatching { nostr().pushLive(0.0, 0.0, false) }; return@withContext }
        if (!validServer(server) || prefs.groupCode.isEmpty()) return@withContext
        try { call(Request.Builder().url("$server/v2/groups/${prefs.groupCode}/live?member=${prefs.memberId}").delete().build()) } catch (_: IOException) { }
    }

    /** One round over the Nostr relays (see [NostrGroupSync]). */
    private suspend fun syncNostr(): Boolean {
        state.update { it.copy(status = SyncUi.Status.SYNCING, pendingUploads = db.unsyncedCount()) }
        return try {
            val g = nostr()
            val store = object : NostrGroupSync.Store {
                override fun ownItems() = db.ownItems()
                override fun insertFromMember(member: String, items: List<RevealItem>) = db.insert(items, "n:$member", synced = true)
                override fun markSynced(ids: Collection<String>) = db.markSynced(ids)
            }
            val c = db.counts()
            val meta = NostrGroupSync.Meta(prefs.nickname, prefs.groupName, c.ownRoads, Math.round(c.ownRoadKm * 10) / 10.0)
            val metaHash = meta.toString()
            val now = System.currentTimeMillis()
            // The member card is only re-sent when it changed or every 6 hours.
            val sendMeta = metaHash != prefs.nostrMetaHash || now - prefs.nostrMetaAt > 6 * 3600_000L
            val published = prefs.nostrPublished
            val r = g.sync(store, published, prefs.nostrSince, if (sendMeta) meta else null)
            prefs.nostrPublished = published
            if (r.metaOk) { prefs.nostrMetaHash = metaHash; prefs.nostrMetaAt = now }
            prefs.nostrSince = r.newest
            if (r.relaysOk == 0) throw IOException("Keine Verbindung zu den Koop-Relays")
            if (r.received.isNotEmpty()) App.notifyDbChanged(remoteRoads = r.received.filter { it.id.startsWith("r:") }.map { it.id })
            val me = g.myPubkey
            val members = r.members.map { m ->
                GroupMember(m.pubkey, m.name, m.roads, m.km, m.lastSeen * 1000, m.live, m.pubkey == me)
            }.let { list -> if (list.any { it.isMe }) list else list + GroupMember(me, prefs.nickname, c.ownRoads, c.ownRoadKm, now, null, true) }
            if (prefs.groupName.isEmpty()) r.members.map { it.groupName }.firstOrNull { it.isNotEmpty() }?.let { prefs.groupName = it }
            prefs.lastSyncMs = now
            android.util.Log.i("FoggyMap", "nostr sync: published ${r.published}, received ${r.received.size}, ${members.size} members, ${r.relaysOk} relay answers")
            state.update { it.copy(status = SyncUi.Status.OK, message = "verschlüsselt über ${minOf(r.relaysOk, relays.urls.size)} Relays",
                lastSync = now, members = members, groupName = prefs.groupName,
                groupRoads = members.sumOf { it.roads }, groupKm = members.sumOf { it.km }, pendingUploads = db.unsyncedCount()) }
            true
        } catch (e: IOException) {
            state.update { it.copy(status = SyncUi.Status.OFFLINE, message = "Offline – wird automatisch nachgeholt", pendingUploads = db.unsyncedCount()) }
            false
        } catch (e: Exception) {
            android.util.Log.w("FoggyMap", "nostr sync failed", e)
            state.update { it.copy(status = SyncUi.Status.ERROR, message = e.message ?: e.javaClass.simpleName) }
            false
        }
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
