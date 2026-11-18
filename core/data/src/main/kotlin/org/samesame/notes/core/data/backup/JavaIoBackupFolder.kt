package org.samesame.notes.core.data.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** [BackupFolder] backed by a plain directory, for tests and local paths. */
class JavaIoBackupFolder(private val root: File, override val displayName: String? = root.name) : BackupFolder {
    init {
        root.mkdirs()
    }

    override fun exists(): Boolean = root.isDirectory

    override fun canWrite(): Boolean = root.canWrite()

    override fun list(path: String): List<BackupEntry> {
        val dir = resolve(path)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty().map { f ->
            object : BackupEntry {
                override val name: String = f.name
                override val isDirectory: Boolean = f.isDirectory
                override val size: Long = if (f.isFile) f.length() else 0L
            }
        }
    }

    override fun isDirectory(path: String): Boolean = resolve(path).isDirectory

    override fun exists(path: String): Boolean = resolve(path).exists()

    override fun createDirectory(path: String): Boolean {
        val dir = resolve(path)
        return dir.isDirectory || dir.mkdirs()
    }

    override fun openOutput(path: String, mimeType: String): OutputStream {
        val file = resolve(path)
        file.parentFile?.mkdirs()
        return file.outputStream()
    }

    override fun openInput(path: String): InputStream = resolve(path).inputStream()

    override fun rename(from: String, to: String): Boolean {
        val src = resolve(from)
        val dst = resolve(to)
        dst.parentFile?.mkdirs()
        if (dst.exists()) dst.deleteRecursively()
        return src.renameTo(dst)
    }

    override fun delete(path: String): Boolean = resolve(path).deleteRecursively()

    private fun resolve(path: String): File {
        val clean = path.trim().trim('/').replace('\\', '/')
        if (clean.isEmpty()) return root
        val out = File(root, clean)
        require(out.canonicalPath.startsWith(root.canonicalPath)) { "Path escapes backup folder: $path" }
        return out
    }
}
