package com.quantumforge.api.source

import com.quantumforge.api.common.BadRequestException
import com.quantumforge.api.config.QuantumForgeProperties
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.URI

/**
 * Validates remote repository URLs. Only https:// to an allowlisted host is
 * accepted, without embedded credentials, so imports cannot be pointed at
 * internal services (SSRF) or at local paths and git transports like ext::.
 */
@Component
class GitUrls(private val properties: QuantumForgeProperties) {
    fun validate(raw: String): String {
        val uri = try {
            URI(raw.trim())
        } catch (e: Exception) {
            throw BadRequestException("invalid_remote_url", "remoteUrl is not a valid URL")
        }
        if (uri.scheme?.lowercase() != "https") throw BadRequestException("invalid_remote_url", "Only https:// repository URLs are supported")
        if (uri.rawUserInfo != null) {
            throw BadRequestException("invalid_remote_url", "Credentials must not be embedded in the URL; use repository credentials instead")
        }
        val host = uri.host?.lowercase() ?: throw BadRequestException("invalid_remote_url", "remoteUrl has no host")
        if (uri.port != -1 && uri.port != 443) throw BadRequestException("invalid_remote_url", "Custom ports are not allowed")
        val allowed = properties.ingestion.git.allowedHosts.map { it.lowercase() }
        if (host !in allowed) {
            throw BadRequestException("host_not_allowed", "Host '$host' is not in the allowed Git hosts", mapOf("allowedHosts" to allowed))
        }
        if (uri.rawQuery != null || uri.rawFragment != null) throw BadRequestException("invalid_remote_url", "Query strings and fragments are not allowed")
        val path = uri.rawPath ?: ""
        if (!Regex("^/[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)+/?$").matches(path) || path.contains("..")) {
            throw BadRequestException("invalid_remote_url", "Unexpected repository path")
        }
        return "https://$host$path"
    }

    /** Resolves the host and refuses private, loopback and link-local targets (cloud metadata). */
    fun assertPublicHost(url: String) {
        val host = URI(url).host
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            throw BadRequestException("host_unresolvable", "Could not resolve $host")
        }
        if (addresses.any { it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isAnyLocalAddress || it.isMulticastAddress }) {
            throw BadRequestException("host_not_allowed", "Repository host resolves to a private address")
        }
    }

    companion object {
        private val safeRef = Regex("^(?!-)(?!.*\\.\\.)(?!.*//)(?!.*@\\{)[A-Za-z0-9._/-]{1,255}(?<!\\.lock)(?<![./])$")

        /** Branch/tag/commit names: no leading dash (option injection), no `..`, git's ref rules. */
        fun requireSafeRef(ref: String): String {
            if (!safeRef.matches(ref)) throw BadRequestException("invalid_ref", "Invalid Git reference '$ref'")
            return ref
        }
    }
}
