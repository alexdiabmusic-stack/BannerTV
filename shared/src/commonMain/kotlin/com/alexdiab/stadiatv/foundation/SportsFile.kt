package com.alexdiab.stadiatv.foundation

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.use

/** Host selects a cache directory. Only provider-validated child filenames are accepted. */
class SportsFile private constructor(private val path: Path) {
    constructor(path: String) : this(path.toPath())
    constructor(folder: SportsFile, name: String) : this(folder.path / name) {
        require(name.isNotEmpty() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' })
    }
    private val fs get() = sportsDisk
    val isFile get() = fs.metadataOrNull(path)?.isRegularFile == true
    val isDirectory get() = fs.metadataOrNull(path)?.isDirectory == true
    val name get() = path.name
    fun exists() = fs.exists(path)
    fun length() = fs.metadataOrNull(path)?.size ?: 0L
    fun lastModified() = fs.metadataOrNull(path)?.lastModifiedAtMillis ?: 0L
    fun mkdirs(): Boolean = runCatching { fs.createDirectories(path); true }.getOrDefault(false)
    fun delete() = runCatching { fs.delete(path); true }.getOrDefault(false)
    fun listFiles(filter: (SportsFile) -> Boolean = { true }): List<SportsFile>? = fs.listOrNull(path)?.map(::SportsFile)?.filter(filter)
    fun readText() = fs.read(path) { readUtf8() }
    fun writeSynced(bytes: ByteArray) = fs.openReadWrite(path).use { handle ->
        handle.resize(0); handle.write(0, bytes, 0, bytes.size); handle.flush()
    }
    fun renameTo(target: SportsFile): Boolean = runCatching { fs.atomicMove(path, target.path); true }.getOrDefault(false)
}
