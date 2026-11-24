package com.inspiredandroid.kai.linux

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.io.File

/** Where an install has got to, in terms both feature UIs can render. */
sealed interface InstallStep {
    data class Download(val fraction: Float) : InstallStep
    data object Extract : InstallStep
    data object Configure : InstallStep

    /** One name for apk (which installs serially), the whole set for apt. */
    data class Packages(val packages: List<String>) : InstallStep
}

private const val UPDATE_TIMEOUT_SECONDS = 300L
private const val PACKAGE_TIMEOUT_SECONDS = 900L

/**
 * Downloads, extracts and bootstraps a rootfs. The chat sandbox and Kai Build
 * both drive this; whoever gets there first produces the install the other one
 * then finds already present.
 */
class LinuxInstaller(private val paths: LinuxPaths) {

    private val downloader = RootfsDownloader(HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 12_000
            socketTimeoutMillis = 45_000
            requestTimeoutMillis = 240_000
        }
    })

    /**
     * Installs [distro] into [paths] and returns the marker it wrote. Cancellable
     * between steps and during the download; a failure or cancellation removes
     * the partial rootfs so the next attempt starts clean.
     */
    suspend fun install(distro: LinuxDistro, onStep: (InstallStep) -> Unit): InstallMarker {
        val spec = DistroSpec.of(distro)
        paths.ensureLayout()
        val proot = File(paths.prootPath)
        check(proot.exists()) {
            "proot binary not found at ${paths.prootPath}. nativeLibraryDir contents: " +
                (File(paths.nativeLibDir).listFiles()?.map { it.name } ?: "empty")
        }
        paths.copyLibtalloc()

        // Wipe any partial/previous install so a retry after a failed package
        // index update (or a distro change) always re-extracts cleanly — and so
        // nothing reading the marker mid-install sees the outgoing install's.
        paths.deleteInstall()
        // deleteInstall() takes the tmp dir with it, and proot binds that as
        // /tmp — without this the install runs with no /tmp at all.
        paths.ensureLayout()

        val archive = paths.archiveFile(spec)
        try {
            onStep(InstallStep.Download(0f))
            val source = downloader.download(spec.rootfsSources(), archive) { onStep(InstallStep.Download(it)) }

            currentCoroutineContext().ensureActive()
            onStep(InstallStep.Extract)
            TarExtractor.extract(archive, paths.rootfsDir, source.prefix)
        } finally {
            archive.delete()
        }

        currentCoroutineContext().ensureActive()
        onStep(InstallStep.Configure)
        spec.configure(paths.rootfsDir, paths.activeDnsServers())
        paths.ensureMountPoints()

        val launcher = launcherFor(spec)
        try {
            if (spec is AlpineSpec) installAlpinePackages(spec, launcher, onStep)
            else {
                refreshPackageIndex(spec, launcher)
                currentCoroutineContext().ensureActive()
                installBasePackages(distro, launcher, onStep)
            }
        } catch (e: Throwable) {
            // A rootfs without its base packages would skip the download on the
            // next attempt and keep failing the same way.
            paths.rootfsDir.deleteRecursively()
            throw e
        }

        val marker = InstallMarker(distro, homeOnRootfs = true)
        paths.writeMarker(marker)
        return marker
    }

    /**
     * A proot for install-time work only. A fresh install always keeps `/root` on
     * the rootfs, so there is nothing to bind over it, and no projects yet.
     */
    private fun launcherFor(spec: DistroSpec) = ProotLauncher(
        prootPath = paths.prootPath,
        libDir = paths.libDir,
        rootfsPath = paths.rootfsDir.absolutePath,
        tmpPath = paths.tmpDir.absolutePath,
        binds = emptyList(),
        extraArgs = spec.prootArgs,
        env = spec.env,
    )

    private suspend fun refreshPackageIndex(spec: DistroSpec, launcher: ProotLauncher) {
        val updateCommand = spec.distro.packageManager.updateCommand
        val result = runInterruptible { launcher.execute(updateCommand, timeoutSeconds = UPDATE_TIMEOUT_SECONDS) }
        check(result.success) { "`$updateCommand` failed: ${result.failureDetail()}" }
    }

    /** Retry the whole transaction; a package download can fail after a successful index request. */
    private suspend fun installAlpinePackages(spec: AlpineSpec, launcher: ProotLauncher, onStep: (InstallStep) -> Unit) {
        val failures = mutableListOf<String>()
        val packages = spec.distro.basePackages
        onStep(InstallStep.Packages(packages))
        for (mirror in spec.mirrors) {
            currentCoroutineContext().ensureActive()
            spec.writeRepositories(paths.rootfsDir, mirror)
            // `add --no-cache` already fetches the indexes; a separate update doubles the fetches.
            val command = spec.distro.packageManager.installCommand(packages).replaceFirst("apk ", "apk --timeout 12 --no-progress ")
            val result = runInterruptible { launcher.execute(command, timeoutSeconds = 50) }
            if (result.success) return
            failures += "${java.net.URI.create(mirror).host}: ${result.failureDetail(260).replace('\n', ' ')}"
        }
        error("Alpine bootstrap could not install ${packages.joinToString()}: ${failures.joinToString("; ")}")
    }

    private suspend fun installBasePackages(
        distro: LinuxDistro,
        launcher: ProotLauncher,
        onStep: (InstallStep) -> Unit,
    ) {
        val manager = distro.packageManager
        // apt resolves the whole set at once, which is both faster and the only
        // way its dependency solver sees the full picture.
        onStep(InstallStep.Packages(distro.basePackages))
        val result = runInterruptible {
            launcher.execute(manager.installCommand(distro.basePackages), timeoutSeconds = PACKAGE_TIMEOUT_SECONDS)
        }
        check(result.success) { "Failed to install base packages: ${result.failureDetail()}" }
    }

    companion object {
        /**
         * Serializes package work across features. A shared rootfs means the chat
         * sandbox's "Install Packages" and a Kai Build agent install can otherwise
         * hit the dpkg lock at the same time and both fail.
         */
        val packageLock = Mutex()
    }
}
