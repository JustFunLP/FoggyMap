package de.foggymap.app.core

/**
 * Grow-only set of reveals (a G-Set CRDT). Merge is union by id: commutative, idempotent and
 * order-independent, so retries, offline queues and two players syncing concurrently can never
 * lose or duplicate progress. An id that already exists is never overwritten.
 */
class RevealSet {
    private val items = LinkedHashMap<String, RevealItem>()

    @Synchronized fun merge(incoming: Collection<RevealItem>): List<RevealItem> {
        val added = ArrayList<RevealItem>()
        for (it in incoming) if (!items.containsKey(it.id)) { items[it.id] = it; added += it }
        return added
    }

    @Synchronized fun ids(): Set<String> = items.keys.toSet()
    @Synchronized fun all(): List<RevealItem> = items.values.toList()
    @Synchronized fun contains(id: String) = items.containsKey(id)
    @Synchronized fun size() = items.size
}
