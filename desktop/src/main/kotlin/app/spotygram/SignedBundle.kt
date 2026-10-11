package app.spotygram

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.json.JSONObject

/** Detached signatures keep macOS's signed application bundle intact. */
object SignedBundle {
    const val MANIFEST = ".spotygram-bundle.json"
    const val SIGNATURE = ".spotygram-bundle.sig"
    private const val MAX_EXPANDED = 1_073_741_824L

    fun remove(directory: File) {
        if (!Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (DesktopTarget.os == "windows" && !attributes.isSymbolicLink)
                    Files.setAttribute(file, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS)
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(directory: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }
    private fun destination(directory: File, name: String): Path {
        require(name.isNotEmpty() && !name.contains('\\'))
        val relative = Path.of(name)
        require(!relative.isAbsolute && relative.normalize() == relative && relative.none { it.toString() == ".." })
        val base = directory.toPath().toAbsolutePath().normalize()
        val target = base.resolve(relative).normalize()
        require(target.startsWith(base) && target != base)
        return target
    }
    fun extract(archive: File, directory: File, version: String, target: String): File {
        remove(directory)
        check(directory.mkdirs())
        val seen = mutableSetOf<String>()
        val links = mutableMapOf<String, String>()
        val hardLinks = mutableMapOf<String, String>()
        var expanded = 0L
        var count = 0
        fun entry(name: String, size: Long, isDirectory: Boolean, link: String?, hardLink: String?, read: (ByteArray) -> Int) {
            require(++count <= 20_000 && size in 0..MAX_EXPANDED)
            val output = destination(directory, name.removeSuffix("/"))
            if (isDirectory) { Files.createDirectories(output); return }
            require(seen.add(name))
            if (link != null) { links[name] = link; return }
            if (hardLink != null) { hardLinks[name] = hardLink; return }
            Files.createDirectories(output.parent)
            var written = 0L
            Files.newOutputStream(output).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val length = read(buffer)
                    if (length < 0) break
                    if (length == 0) continue
                    written += length
                    expanded += length
                    require(expanded <= MAX_EXPANDED && written <= MAX_EXPANDED)
                    stream.write(buffer, 0, length)
                }
            }
            if (size > 0) require(written == size)
        }
        try {
            if (archive.name.endsWith(".zip")) {
                ZipArchiveInputStream(archive.inputStream()).use { input ->
                    while (true) {
                        val item = input.nextZipEntry ?: break
                        require(input.canReadEntryData(item))
                        if (item.isUnixSymlink) {
                            val value = input.readNBytes(4097)
                            require(value.size <= 4096)
                            entry(item.name, value.size.toLong(), false, value.toString(Charsets.UTF_8), null) { -1 }
                        } else entry(item.name, item.size.coerceAtLeast(0), item.isDirectory, null, null, input::read)
                    }
                }
            } else {
                TarArchiveInputStream(GZIPInputStream(archive.inputStream())).use { input ->
                    while (true) {
                        val item = input.nextTarEntry ?: break
                        require(input.canReadEntryData(item) && (item.isFile || item.isDirectory || item.isSymbolicLink || item.isLink))
                        entry(item.name, item.size, item.isDirectory,
                            item.linkName.takeIf { item.isSymbolicLink }, item.linkName.takeIf { item.isLink }, input::read)
                    }
                }
            }
            hardLinks.forEach { (name, source) ->
                val from = destination(directory, source)
                require(Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS))
                expanded += Files.size(from)
                require(expanded <= MAX_EXPANDED)
                val to = destination(directory, name)
                Files.createDirectories(to.parent)
                Files.copy(from, to)
            }
            val root = verify(directory, version, target, links)
            return root
        } catch (error: Throwable) { remove(directory); throw error }
    }

    fun verify(directory: File, version: String, target: String, pendingLinks: Map<String, String>? = null): File {
        val metadata = File(directory, MANIFEST)
        val signed = File(directory, SIGNATURE)
        require(metadata.length() in 1..1_048_576 && signed.length() == 64L)
        val bytes = metadata.readBytes()
        val publicText = checkNotNull(javaClass.classLoader.getResourceAsStream("update-key.pub"))
            .bufferedReader().use { it.readText() }.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicText)))
        require(Signature.getInstance("Ed25519").run { initVerify(key); update(bytes); verify(signed.readBytes()) })
        val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
        require(manifest.getInt("format") == 1 && manifest.getString("application") == "app.spotygram" &&
            manifest.getString("version") == version && manifest.getString("target") == target)
        val rootName = manifest.getString("root")
        require(rootName == if (target.startsWith("macos")) "Spotygram.app" else "Spotygram")
        val root = File(directory, rootName)
        require(root.isDirectory)
        val files = manifest.getJSONArray("files")
        require(files.length() in 1..20_000)
        val expected = mutableSetOf<String>()
        val symlinks = mutableListOf<Pair<Path, String>>()
        for (i in 0 until files.length()) {
            val description = files.getJSONObject(i)
            val name = description.getString("path")
            require(expected.add(name))
            val path = destination(root, name)
            if (description.has("link")) {
                val targetName = description.getString("link")
                val relative = Path.of(targetName)
                require(!relative.isAbsolute && path.parent.resolve(relative).normalize().startsWith(root.toPath().toAbsolutePath().normalize()))
                if (pendingLinks != null) require(pendingLinks["$rootName/$name"] == targetName)
                else require(Files.isSymbolicLink(path) && Files.readSymbolicLink(path).toString() == targetName)
                symlinks += path to targetName
            } else {
                require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                val expectedHash = description.getString("sha256")
                require(Regex("[0-9a-f]{64}").matches(expectedHash) && sha256(path.toFile()) == expectedHash)
                if (DesktopTarget.os != "windows") check(path.toFile().setExecutable(description.getBoolean("executable"), false))
            }
        }
        if (pendingLinks != null) {
            require(pendingLinks.keys == symlinks.map { "$rootName/${root.toPath().toAbsolutePath().relativize(it.first).toString().replace('\\', '/')}" }.toSet())
            symlinks.forEach { (path, targetName) ->
                Files.createDirectories(path.parent)
                Files.createSymbolicLink(path, Path.of(targetName))
            }
        }
        val actual = mutableSetOf<String>()
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                actual += root.toPath().relativize(path).toString().replace('\\', '/')
                return FileVisitResult.CONTINUE
            }
        })
        require(actual == expected)
        val top = directory.listFiles().orEmpty().map { it.name }.toSet()
        require(top == setOf(rootName, MANIFEST, SIGNATURE))
        return root
    }
    fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
