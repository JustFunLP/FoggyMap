package de.foggymap.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.foggymap.app.core.BBox
import de.foggymap.app.core.BuildingRevealer
import de.foggymap.app.core.RevealItem
import de.foggymap.app.core.RevealKind
import org.json.JSONObject

/**
 * Local, permanent store of everything revealed (own and synced from the group).
 * `seq` gives the map an incremental change feed; `synced = 0` is the offline outbox.
 */
class RevealDatabase(context: Context) : SQLiteOpenHelper(context, "foggymap.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE reveal (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              id TEXT NOT NULL UNIQUE,
              kind TEXT NOT NULL,
              data TEXT NOT NULL,
              len REAL NOT NULL DEFAULT 0,
              mlat REAL, mlon REAL,
              s REAL, w REAL, n REAL, e REAL,
              owner TEXT NOT NULL,
              created INTEGER NOT NULL,
              synced INTEGER NOT NULL DEFAULT 0
            )""".trimIndent())
        db.execSQL("CREATE INDEX reveal_outbox ON reveal(synced)")
        db.execSQL("CREATE INDEX reveal_kind ON reveal(kind)")
        db.execSQL("CREATE INDEX reveal_bbox ON reveal(s, n)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Bounding boxes, so that lazily loaded building tiles can find the revealed areas they touch.
            for (c in listOf("s", "w", "n", "e")) db.execSQL("ALTER TABLE reveal ADD COLUMN $c REAL")
            db.execSQL("CREATE INDEX reveal_bbox ON reveal(s, n)")
            db.rawQuery("SELECT seq, data FROM reveal", null).use { cur ->
                while (cur.moveToNext()) {
                    val item = RevealItem.fromJson(JSONObject(cur.getString(1))) ?: continue
                    val b = BuildingRevealer.bboxOf(listOf(item)) ?: continue
                    db.execSQL("UPDATE reveal SET s = ?, w = ?, n = ?, e = ? WHERE seq = ?",
                        arrayOf<Any>(b.south, b.west, b.north, b.east, cur.getLong(0)))
                }
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    /** Inserts unknown items, ignores known ids (never overwrites). Returns only the newly stored items. */
    @Synchronized
    fun insert(items: Collection<RevealItem>, owner: String, synced: Boolean): List<RevealItem> {
        if (items.isEmpty()) return emptyList()
        val added = ArrayList<RevealItem>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (it in items) {
                val mid = it.line?.let { l -> l[l.size / 2] } ?: it.polygons.firstOrNull()?.firstOrNull()?.firstOrNull()
                val cv = ContentValues().apply {
                    put("id", it.id); put("kind", it.kind.code); put("data", it.toJson().toString())
                    put("len", it.length); put("owner", owner); put("created", now); put("synced", if (synced) 1 else 0)
                    if (mid != null) { put("mlat", mid.lat); put("mlon", mid.lon) }
                    BuildingRevealer.bboxOf(listOf(it))?.let { b -> put("s", b.south); put("w", b.west); put("n", b.north); put("e", b.east) }
                }
                if (db.insertWithOnConflict("reveal", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added += it
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added
    }

    /** Items with seq > [after], in insertion order. Returns the highest seq seen. */
    fun loadAfter(after: Long, limit: Int = 5000): Pair<Long, List<RevealItem>> {
        val out = ArrayList<RevealItem>()
        var max = after
        readableDatabase.rawQuery("SELECT seq, data FROM reveal WHERE seq > ? ORDER BY seq LIMIT ?",
            arrayOf(after.toString(), limit.toString())).use { c ->
            while (c.moveToNext()) {
                max = c.getLong(0)
                RevealItem.fromJson(JSONObject(c.getString(1)))?.let(out::add)
            }
        }
        return max to out
    }

    /** Revealed areas (not buildings) whose bounding box overlaps [b]. */
    fun areasIntersecting(b: BBox): List<RevealItem> {
        val out = ArrayList<RevealItem>()
        readableDatabase.rawQuery(
            "SELECT data FROM reveal WHERE kind != 'g' AND s <= ? AND n >= ? AND w <= ? AND e >= ?",
            arrayOf(b.north.toString(), b.south.toString(), b.east.toString(), b.west.toString())).use { c ->
            while (c.moveToNext()) RevealItem.fromJson(JSONObject(c.getString(0)))?.let(out::add)
        }
        return out
    }

    fun unsynced(limit: Int): List<RevealItem> {
        val out = ArrayList<RevealItem>()
        readableDatabase.rawQuery("SELECT data FROM reveal WHERE synced = 0 ORDER BY seq LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) RevealItem.fromJson(JSONObject(c.getString(0)))?.let(out::add)
        }
        return out
    }

    fun unsyncedCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM reveal WHERE synced = 0", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    @Synchronized
    fun markSynced(ids: Collection<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { db.execSQL("UPDATE reveal SET synced = 1 WHERE id = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** After joining another group everything (own and inherited) is offered to the new group. */
    @Synchronized fun markAllUnsynced() { writableDatabase.execSQL("UPDATE reveal SET synced = 0") }

    fun roadIds(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT id FROM reveal WHERE kind = 'r'", null).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    data class Counts(
        val roads: Int, val roadKm: Double, val blocks: Int, val buildings: Int, val areas: Int,
        val ownRoads: Int, val ownRoadKm: Double, val total: Int,
    )

    fun counts(): Counts {
        var roads = 0; var km = 0.0; var blocks = 0; var buildings = 0; var areas = 0; var ownRoads = 0; var ownKm = 0.0; var total = 0
        readableDatabase.rawQuery("SELECT kind, owner = '$OWNER_ME', COUNT(*), SUM(len) FROM reveal GROUP BY kind, owner = '$OWNER_ME'", null).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(2); val len = c.getDouble(3) / 1000.0
                val own = c.getInt(1) == 1
                total += n
                when (RevealKind.of(c.getString(0))) {
                    RevealKind.ROAD -> { roads += n; km += len; if (own) { ownRoads += n; ownKm += len } }
                    RevealKind.BLOCK -> blocks += n
                    RevealKind.BUILDING -> buildings += n
                    RevealKind.AREA -> areas += n
                    null -> {}
                }
            }
        }
        return Counts(roads, km, blocks, buildings, areas, ownRoads, ownKm, total)
    }

    /** Explored street metres per map cell name (by segment midpoint), for the exploration percentage. */
    fun roadLengthByCell(cellOf: (Double, Double) -> String): Map<String, Double> {
        val out = HashMap<String, Double>()
        readableDatabase.rawQuery("SELECT mlat, mlon, len FROM reveal WHERE kind = 'r' AND mlat IS NOT NULL", null).use { c ->
            while (c.moveToNext()) {
                val k = cellOf(c.getDouble(0), c.getDouble(1))
                out[k] = (out[k] ?: 0.0) + c.getDouble(2)
            }
        }
        return out
    }

    /** Most recent own road positions (to centre the map on start without GPS). */
    fun lastPosition(): Pair<Double, Double>? =
        readableDatabase.rawQuery("SELECT mlat, mlon FROM reveal WHERE mlat IS NOT NULL ORDER BY seq DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getDouble(0) to c.getDouble(1) else null
        }

    @Synchronized fun clear() { writableDatabase.execSQL("DELETE FROM reveal") }

    companion object { const val OWNER_ME = "me" }
}
