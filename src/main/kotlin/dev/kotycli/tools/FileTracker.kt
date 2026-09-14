package dev.kotycli.tools

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap

/** Ficheros leídos en esta sesión y su mtime, para que `edit` exija lectura previa y detecte cambios externos. */
class FileTracker {
    private val seen = ConcurrentHashMap<Path, FileTime>()

    fun record(path: Path) {
        val key = key(path)
        seen[key] = runCatching { Files.getLastModifiedTime(key) }.getOrElse { FileTime.fromMillis(0) }
    }

    fun wasRead(path: Path): Boolean = seen.containsKey(key(path))

    /** true si el fichero cambió en disco desde la última lectura registrada. */
    fun isStale(path: Path): Boolean {
        val key = key(path)
        val recorded = seen[key] ?: return true
        val now = runCatching { Files.getLastModifiedTime(key) }.getOrNull() ?: return true
        return now != recorded
    }

    private fun key(path: Path): Path = runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }
}
