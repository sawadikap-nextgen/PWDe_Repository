package com.pwde.app.data.remote

/** One Firestore document: its id and fields. Numbers come back as Long/Double, whatever was written. */
data class CloudDoc(val id: String, val data: Map<String, Any?>) {
    val updatedAt: Long get() = data.long(FIELD_UPDATED_AT) ?: 0L
    val deleted: Boolean get() = data[FIELD_DELETED] == true

    companion object {
        const val FIELD_UPDATED_AT = "updatedAt"
        const val FIELD_DELETED = "deleted"
    }
}

/** What to do with one record so this phone and the cloud agree. */
sealed interface MergeStep<L> {
    /** Upload [local] (it's new, newer, or missing from the cloud). */
    data class Push<L>(val local: L) : MergeStep<L>

    /** Take [remote] over [local] (null: it's new to this phone). */
    data class Pull<L>(val remote: CloudDoc, val local: L?) : MergeStep<L>

    /** The cloud deleted [local] after its last change here. */
    data class DeleteLocal<L>(val local: L) : MergeStep<L>
}

/**
 * Last-write-wins merge of local records with their cloud documents, matched by remote id. A local
 * record without a remote id has never been uploaded. Deleted cloud documents are tombstones: they
 * delete the local copy unless it was changed after the delete.
 */
fun <L> planMerge(
    local: List<L>,
    remote: List<CloudDoc>,
    remoteIdOf: (L) -> String?,
    updatedAtOf: (L) -> Long,
): List<MergeStep<L>> {
    val remoteById = remote.associateBy { it.id }
    val matched = HashSet<String>()
    val steps = mutableListOf<MergeStep<L>>()
    for (record in local) {
        val doc = remoteIdOf(record)?.let(remoteById::get)
        if (doc == null) {
            steps += MergeStep.Push(record)
            continue
        }
        matched += doc.id
        val localAt = updatedAtOf(record)
        when {
            doc.deleted -> steps += if (doc.updatedAt >= localAt) MergeStep.DeleteLocal(record) else MergeStep.Push(record)
            localAt > doc.updatedAt -> steps += MergeStep.Push(record)
            doc.updatedAt > localAt -> steps += MergeStep.Pull(doc, record)
        }
    }
    remote.filter { it.id !in matched && !it.deleted }.forEach { steps += MergeStep.Pull(it, null) }
    return steps
}

internal fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
internal fun Map<String, Any?>.int(key: String, default: Int): Int = (this[key] as? Number)?.toInt() ?: default
internal fun Map<String, Any?>.float(key: String, default: Float): Float = (this[key] as? Number)?.toFloat() ?: default
internal fun Map<String, Any?>.bool(key: String, default: Boolean): Boolean = this[key] as? Boolean ?: default
internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String
internal fun Map<String, Any?>.strings(key: String): List<String> = (this[key] as? List<*>)?.filterIsInstance<String>().orEmpty()
