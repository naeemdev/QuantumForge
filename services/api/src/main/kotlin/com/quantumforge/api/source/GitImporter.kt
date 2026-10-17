package com.quantumforge.api.source

import com.quantumforge.api.common.ApiException
import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.config.QuantumForgeProperties
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class GitCheckout(val directory: Path, val commit: String, val ref: String?)

/**
 * Shallow-clones public HTTPS repositories. git is invoked with an argument
 * vector (never through a shell), with hooks, credential helpers, prompts,
 * non-HTTPS protocols, submodules and LFS smudging disabled, and with a scrubbed
 * environment so host credentials cannot leak into the clone.
 */
@Component
class GitImporter(private val properties: QuantumForgeProperties, private val gitUrls: GitUrls) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun clone(remoteUrl: String, ref: String?, workDir: Path): GitCheckout {
        val git = properties.ingestion.git
        if (!git.enabled) throw BadRequestException("git_import_disabled", "Git import is disabled on this installation")
        val url = gitUrls.validate(remoteUrl)
        gitUrls.assertPublicHost(url)
        ref?.let { GitUrls.requireSafeRef(it) }

        val target = workDir.resolve("checkout")
        val home = Files.createDirectories(workDir.resolve("home"))
        val args = buildList {
            add(git.executable)
            addAll(hardeningFlags())
            add("clone")
            addAll(listOf("--depth", "1", "--single-branch", "--no-tags", "--no-recurse-submodules", "--quiet"))
            if (ref != null) addAll(listOf("--branch", ref))
            add("--")
            add(url)
            add(target.toString())
        }
        run(args, workDir, home, git.timeout.toSeconds())
        val commit = run(listOf(git.executable) + hardeningFlags() + listOf("-C", target.toString(), "rev-parse", "HEAD"), workDir, home, 30).trim()
        if (!Regex("^[0-9a-f]{40}([0-9a-f]{24})?$").matches(commit)) throw IllegalStateException("Unexpected rev-parse output")
        return GitCheckout(target, commit, ref)
    }

    private fun hardeningFlags() = listOf(
        "-c", "core.hooksPath=/dev/null",
        "-c", "protocol.allow=never",
        "-c", "protocol.https.allow=always",
        "-c", "credential.helper=",
        "-c", "core.askPass=",
        "-c", "http.followRedirects=false",
        "-c", "submodule.recurse=false",
        "-c", "core.fsmonitor=false",
    )

    private fun run(args: List<String>, workDir: Path, home: Path, timeoutSeconds: Long): String {
        val output = Files.createTempFile(workDir, "git-", ".log")
        val process = ProcessBuilder(args)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .also { pb ->
                val env = pb.environment()
                env.clear()
                env["PATH"] = System.getenv("PATH") ?: "/usr/bin:/bin"
                env["HOME"] = home.toString()
                env["GIT_TERMINAL_PROMPT"] = "0"
                env["GIT_CONFIG_NOSYSTEM"] = "1"
                env["GIT_CONFIG_GLOBAL"] = "/dev/null"
                env["GIT_LFS_SKIP_SMUDGE"] = "1"
                env["GIT_ASKPASS"] = "/bin/false"
                System.getenv("HTTPS_PROXY")?.let { env["HTTPS_PROXY"] = it }
                System.getenv("NO_PROXY")?.let { env["NO_PROXY"] = it }
                System.getenv("GIT_SSL_CAINFO")?.let { env["GIT_SSL_CAINFO"] = it }
            }
            .start()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            throw ApiException(HttpStatus.GATEWAY_TIMEOUT, "git_timeout", "Git operation exceeded ${timeoutSeconds}s")
        }
        val text = Files.readString(output).take(4000)
        if (process.exitValue() != 0) {
            log.info("git exited with {}: {}", process.exitValue(), text)
            throw ApiException(HttpStatus.BAD_GATEWAY, "git_failed", "Could not fetch the repository. Check the URL and reference.", mapOf("output" to redact(text)))
        }
        return text
    }

    private fun redact(text: String) = text.replace(Regex("https://[^@\\s]+@"), "https://***@")
}
