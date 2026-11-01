package org.samesame.notes.core.data.lock

import android.content.Context
import org.samesame.notes.core.security.LockConfigStorage
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** `noBackupFilesDir/lock/lock.bin`: excluded from backups, so a restore never pairs notes with a foreign vault. */
internal class FileLockConfigStorage(private val file: File) : LockConfigStorage {
    constructor(context: Context) : this(File(File(context.noBackupFilesDir, "lock"), "lock.bin"))

    override fun read(): ByteArray? = file.takeIf { it.isFile }?.readBytes()

    override fun write(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
