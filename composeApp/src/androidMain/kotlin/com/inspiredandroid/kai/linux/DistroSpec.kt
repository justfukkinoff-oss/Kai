package com.inspiredandroid.kai.linux

import android.os.Build
import java.io.File
import java.net.URL

// Cap at 3.22: Alpine 3.23+ ships apk-tools 3, which uses execveat() in a way
// proot does not support, so `apk update` fails under the sandbox runtime.
// See termux/proot-distro#532 / #595.
private const val ALPINE_VERSION = "3.22.5"
private const val ALPINE_BRANCH = "v3.22"

private val ALPINE_MIRRORS = listOf(
    "https://dl-cdn.alpinelinux.org/alpine",
    "https://mirrors.edge.kernel.org/alpine",
    "https://ftp.halifax.rwth-aachen.de/alpine",
    "https://alpine.ethz.ch/alpine",
    "https://mirror.csclub.uwaterloo.ca/alpine",
    "https://mirrors.tuna.tsinghua.edu.cn/alpine",
)

private const val LXC_INDEX = "https://images.linuxcontainers.org/meta/1.0/index-user"
private const val LXC_BASE = "https://images.linuxcontainers.org"
private const val DEBIAN_RELEASE = "bookworm"
private const val TERMUX_DEBIAN_RELEASE = "v4.7.0"

data class RootfsSource(val url: String, val prefix: String = "", val sha256: String? = null)

/**
 * The per-distribution facts the shared installer and proot launcher need:
 * where the rootfs comes from, what has to be fixed up after extraction, and
 * which proot flags and environment the distro's own tooling depends on.
 */
sealed interface DistroSpec {

    val distro: LinuxDistro

    /** This device's ABI in the distro's own architecture vocabulary. */
    fun arch(): String

    /** File name for the downloaded archive — the extension picks the decompressor. */
    val archiveName: String

    /**
     * Candidate download URLs, best first. May hit the network to resolve an
     * index, so call it off the main thread.
     */
    fun rootfsSources(): List<RootfsSource>

    /** Post-extraction fixes that must land before the first proot run. */
    fun configure(rootfsDir: File, dnsServers: List<String>)

    /** proot flags this distro cannot work without. */
    val prootArgs: List<String> get() = emptyList()

    /** Environment every command in this distro should see. */
    val env: Map<String, String> get() = emptyMap()

    companion object {
        fun of(distro: LinuxDistro): DistroSpec = when (distro) {
            LinuxDistro.ALPINE -> AlpineSpec
            LinuxDistro.DEBIAN -> DebianSpec
        }
    }
}

object AlpineSpec : DistroSpec {

    override val distro = LinuxDistro.ALPINE

    override val archiveName = "rootfs.tar.gz"

    override fun arch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.startsWith("arm64") -> "aarch64"
            abi.startsWith("armeabi") -> "armhf"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> "aarch64"
        }
    }

    override fun rootfsSources(): List<RootfsSource> {
        val arch = arch()
        return ALPINE_MIRRORS.map { base ->
            RootfsSource("$base/$ALPINE_BRANCH/releases/$arch/alpine-minirootfs-$ALPINE_VERSION-$arch.tar.gz")
        }
    }

    override fun configure(rootfsDir: File, dnsServers: List<String>) {
        TarExtractor.makeWritable(rootfsDir)
        TarExtractor.writeResolvConf(rootfsDir, dnsServers)
        writeRepositories(rootfsDir, ALPINE_MIRRORS.first())
    }

    /**
     * `apk update` is the first thing that has to work, and a mirror can be
     * unreachable even when the one that served the rootfs was fine. The
     * installer walks these, rewriting `repositories` each time.
     */
    val mirrors: List<String> = ALPINE_MIRRORS

    fun writeRepositories(rootfsDir: File, mirrorBase: String) {
        val apkDir = File(rootfsDir, "etc/apk")
        apkDir.mkdirs()
        File(apkDir, "repositories").writeText(
            "$mirrorBase/$ALPINE_BRANCH/main\n$mirrorBase/$ALPINE_BRANCH/community\n",
        )
    }
}

object DebianSpec : DistroSpec {

    override val distro = LinuxDistro.DEBIAN

    override val archiveName = "rootfs.tar.xz"

    override fun arch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return when {
            abi.startsWith("arm64") -> "arm64"
            abi.startsWith("armeabi") -> "armhf"
            abi.startsWith("x86_64") -> "amd64"
            abi.startsWith("x86") -> "i386"
            else -> "arm64"
        }
    }

    /**
     * Newest default image for this device's architecture, e.g.
     * `debian;bookworm;arm64;default;20260731_05:24;/images/debian/bookworm/arm64/default/20260731_05:24/`.
     */
    override fun rootfsSources(): List<RootfsSource> {
        val arch = arch()
        val lxc = runCatching {
            val connection = URL(LXC_INDEX).openConnection().apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            connection.getInputStream().bufferedReader().useLines { lines ->
                val pattern = Regex("/images/debian/$DEBIAN_RELEASE/$arch/default/\\d{8}_\\d{2}:\\d{2}/?")
                lines.filter { it.startsWith("debian;$DEBIAN_RELEASE;$arch;default;") }
                    .mapNotNull { it.split(';').getOrNull(5)?.trim()?.takeIf(pattern::matches) }
                    .distinct().sortedDescending().take(2)
                    .map { RootfsSource("$LXC_BASE${it.trimEnd('/')}/rootfs.tar.xz") }.toList()
            }
        }.getOrDefault(emptyList())
        // The Termux project publishes a PRoot-compatible Bookworm rootfs independently of LXC.
        val (abi, sha) = when (arch) {
            "armhf" -> "arm" to "0eba2cb93261d6e73c2f3c32ed7ebe9de408ceef584c5e0c0b7e237d294f7a8d"
            "i386" -> "i686" to "7425f5fe7f34c718428f235b9155adb782c29ce6347f704f4a93a9da195b9aa3"
            "amd64" -> "x86_64" to "164932ab77a0b94a8e355c9b68158a5b76d5abef89ada509488c44ff54655d61"
            else -> "aarch64" to "4baa32280cc70b67e2c650777c1d974349f0cdf23afaabc305ad3bc6182b8df8"
        }
        val prefix = "debian-$DEBIAN_RELEASE-$abi"
        return lxc + RootfsSource(
            "https://github.com/termux/proot-distro/releases/download/$TERMUX_DEBIAN_RELEASE/$prefix-pd-$TERMUX_DEBIAN_RELEASE.tar.xz",
            prefix, sha,
        )
    }

    override fun configure(rootfsDir: File, dnsServers: List<String>) {
        TarExtractor.makeWritable(rootfsDir)
        TarExtractor.writeResolvConf(rootfsDir, dnsServers)
        // apt and dpkg assume these exist; an LXC image ships some of them empty
        // and the tar extractor skips empty directories it never saw an entry for.
        listOf(
            "var/lib/apt/lists/partial",
            "var/cache/apt/archives/partial",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/info",
            "var/lib/dpkg/alternatives",
            "var/log",
            "run/lock",
            "tmp",
        ).forEach { File(rootfsDir, it).mkdirs() }
        // dpkg fsyncs every unpacked file by default, which on a phone turns a
        // base install into a multi-minute affair.
        File(rootfsDir, "etc/dpkg/dpkg.cfg.d").mkdirs()
        File(rootfsDir, "etc/dpkg/dpkg.cfg.d/force-unsafe-io").writeText("force-unsafe-io\n")
        // apt drops to `_apt` via setresuid for downloads. Work profiles and
        // system clones block that syscall (termux/proot#369), so `apt-get update`
        // fails and every package then looks missing. proot's root is fake anyway,
        // so staying root costs nothing; the file also covers shell-run apt-get.
        File(rootfsDir, "etc/apt/apt.conf.d").mkdirs()
        File(rootfsDir, "etc/apt/apt.conf.d/99kai-nosandbox").writeText("APT::Sandbox::User \"root\";\n")
    }

    /**
     * dpkg unpacks packages via hardlinks, which Android's `protected_hardlinks`
     * policy refuses inside the app sandbox. Without the emulation the base
     * install fails with a dpkg subprocess error even though `apt-get update`
     * succeeded. `-L` is the companion lstat fix.
     */
    override val prootArgs = listOf("--link2symlink", "-L")

    override val env = mapOf("DEBIAN_FRONTEND" to "noninteractive")
}
