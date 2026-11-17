package org.samesame.notes.core.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.samesame.notes.core.model.Limits

/** One child of a document tree. [id] is the source's opaque document id; [name] is the provider's display name. */
data class UploadChild<out T>(val id: T, val name: String, val isDirectory: Boolean)

/**
 * A minimal view of a document tree, so the walk can be tested on the JVM without SAF. The Android implementation
 * queries `DocumentsContract.buildChildDocumentsUriUsingTree` and runs on `Dispatchers.IO`.
 */
interface UploadTreeSource<T> {
    /** Direct children of [parentId]; null = the picked tree root. */
    suspend fun children(parentId: T?): List<UploadChild<T>>
}

/** A materialised node. [value] is the source id, [children] is empty for a file (and for an empty folder). */
data class UploadNode<out T>(
    val name: String,
    val value: T,
    val isDirectory: Boolean,
    val children: List<UploadNode<T>> = emptyList(),
)

/**
 * Walks the tree depth-first, at most [Limits.MAX_FOLDER_DEPTH] folder levels. An unreadable folder contributes no
 * children. Cancellation stops the walk.
 */
suspend fun <T> readUploadTree(source: UploadTreeSource<T>, rootId: T? = null): List<UploadNode<T>> {
    suspend fun walk(parent: T?, depth: Int): List<UploadNode<T>> {
        currentCoroutineContext().ensureActive()
        if (depth >= Limits.MAX_FOLDER_DEPTH) return emptyList()
        return source.children(parent).map { child ->
            currentCoroutineContext().ensureActive()
            if (child.isDirectory) UploadNode(child.name, child.id, true, walk(child.id, depth + 1))
            else UploadNode(child.name, child.id, false)
        }
    }
    return walk(rootId, 0)
}
