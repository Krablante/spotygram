package app.spotygram

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** The helper runs from cache, so Windows releases every file in the old installation. */
object DesktopUpdater {
    data class Helper(val directory: File, val process: Process)
    fun installedRoot(): File? {
        var current: File? = DesktopTarget.resources.absoluteFile
        repeat(6) {
            val candidate = current ?: return null
            if (isInstallation(candidate)) return candidate
            current = candidate.parentFile
        }
        return null
    }
    private fun isInstallation(root: File): Boolean = when (DesktopTarget.os) {
        "windows" -> root.name == "Spotygram" && File(root, "Spotygram.exe").isFile
        "macos" -> root.name == "Spotygram.app" && File(root, "Contents/MacOS/Spotygram").isFile
        else -> root.name == "Spotygram" && File(root, "bin/Spotygram").isFile
    }
    private fun copy(source: File, destination: File) {
        Files.walkFileTree(source.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.createDirectories(destination.toPath().resolve(source.toPath().relativize(path)))
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                val target = destination.toPath().resolve(source.toPath().relativize(path))
                if (attributes.isSymbolicLink) Files.createSymbolicLink(target, Files.readSymbolicLink(path))
                else Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
    }
    fun launchHelper(app: SpotygramApp, staging: File, installed: File, asset: UpdateAsset): Helper {
        require(isInstallation(installed))
        val job = File(app.cacheDir, "updates/helper-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val description = JSONObject().put("pid", ProcessHandle.current().pid())
            .put("staging", staging.absolutePath).put("installed", installed.absolutePath)
            .put("version", asset.version).put("target", asset.abi)
        File(job, "job.json").writeText(description.toString())
        val runtime = File(job, installed.name)
        copy(installed, runtime)
        val process = launcher(runtime, "--apply-update", job.absolutePath).directory(job).redirectErrorStream(true)
            .redirectOutput(File(job, "helper.log")).start()
        File(job, "helper-process.json").writeText(JSONObject().put("pid", process.pid())
            .put("started", process.info().startInstant().orElseThrow().toString()).toString())
        return Helper(job, process)
    }
    fun abort(helper: Helper) {
        stop(helper.process)
        SignedBundle.remove(helper.directory)
    }
    private fun stop(process: Process) {
        // Windows jpackage launchers can host the JVM in a child process.
        // Capture descendants before stopping the launcher, while ownership is still known.
        val family = process.toHandle().descendants().use { it.toList() }.asReversed() + process.toHandle()
        family.filter { it.isAlive }.forEach { it.destroy() }
        for (member in family) {
            if (!member.isAlive) continue
            try { member.onExit().get(5, TimeUnit.SECONDS) }
            catch (_: java.util.concurrent.TimeoutException) {
                member.destroyForcibly()
                member.onExit().get(5, TimeUnit.SECONDS)
            }
        }
    }
    fun finishHelper(job: File): Boolean {
        if (!job.isDirectory) return true
        val record = File(job, "helper-process.json")
        if (record.isFile) {
            val info = JSONObject(record.readText())
            val process = ProcessHandle.of(info.getLong("pid")).orElse(null)
            if (process != null && process.info().startInstant().map { it.toString() }.orElse("") == info.getString("started")) {
                try { process.onExit().get(90, TimeUnit.SECONDS) }
                catch (_: java.util.concurrent.TimeoutException) { return false }
            }
        }
        SignedBundle.remove(job)
        return true
    }
    fun apply(job: File) {
        require(job.name.startsWith("helper-") && job.parentFile.name == "updates")
        val description = JSONObject(File(job, "job.json").readText())
        val staging = File(description.getString("staging")).canonicalFile
        val installed = File(description.getString("installed")).canonicalFile
        require(staging == File(job.parentFile, "staging").canonicalFile && isInstallation(installed))
        val version = description.getString("version")
        val target = description.getString("target")
        require(target == DesktopTarget.id)
        SignedBundle.verify(staging, version, target)
        File(job, "ready").writeText("ready")
        ProcessHandle.of(description.getLong("pid")).ifPresent { process ->
            process.onExit().get(60, TimeUnit.SECONDS)
        }
        val prepared = File(installed.parentFile, ".${installed.name}.update-${UUID.randomUUID()}")
        val backup = File(installed.parentFile, ".${installed.name}.previous-${UUID.randomUUID()}")
        check(prepared.mkdirs())
        try {
            val original = SignedBundle.verify(staging, version, target)
            val replacement = File(prepared, original.name)
            copy(original, replacement)
            Files.copy(File(staging, SignedBundle.MANIFEST).toPath(), File(prepared, SignedBundle.MANIFEST).toPath())
            Files.copy(File(staging, SignedBundle.SIGNATURE).toPath(), File(prepared, SignedBundle.SIGNATURE).toPath())
            SignedBundle.verify(prepared, version, target)
            Files.move(installed.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
            try { Files.move(replacement.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (error: Throwable) {
                Files.move(backup.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE)
                throw error
            }
            var process: Process? = null
            try {
                process = launcher(installed, "--update-job", job.absolutePath).start()
                val deadline = System.nanoTime() + 60_000_000_000L
                while (!File(job, "acknowledged").isFile && process.isAlive && System.nanoTime() < deadline)
                    Thread.sleep(200)
                check(File(job, "acknowledged").isFile) { "Updated application did not acknowledge startup" }
            } catch (error: Throwable) {
                process?.let(::stop)
                SignedBundle.remove(installed)
                Files.move(backup.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE)
                launcher(installed).start()
                throw IllegalStateException("Restored the previous application after failed startup", error)
            }
            SignedBundle.remove(backup)
            File(job, "finished").writeText("installed")
        } finally { SignedBundle.remove(prepared) }
    }
    fun acknowledge(job: File) {
        require(job.name.startsWith("helper-") && job.parentFile.name == "updates")
        val description = JSONObject(File(job, "job.json").readText())
        require(description.getString("version") == BuildConfig.VERSION_NAME && description.getString("target") == DesktopTarget.id)
        File(job, "acknowledged").writeText("started")
    }
    private fun executable(root: File) = when (DesktopTarget.os) {
        "windows" -> File(root, "Spotygram.exe")
        "macos" -> File(root, "Contents/MacOS/Spotygram")
        else -> File(root, "bin/Spotygram")
    }
    private fun launcher(root: File, vararg arguments: String) =
        ProcessBuilder(executable(root).absolutePath, *arguments).directory(root).apply {
            // A native jpackage launcher sets this itself; inheriting it breaks Linux child argument parsing.
            environment().remove("_JPACKAGE_LAUNCHER")
        }
}
