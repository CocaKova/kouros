package com.cocakova.kouros.net

import java.net.URI

/**
 * Whether a server URL can be used without the user explicitly accepting a risk.
 *
 * ComfyUI has no authentication of its own, so the address it listens on *is* its security.
 * https anywhere is fine. Plain http is fine on private networks — RFC 1918, carrier-grade NAT
 * (which includes Tailscale's 100.64/10), link-local, loopback, IPv6 ULA and mDNS `.local` names —
 * and needs an explicit opt-in anywhere else, because prompts, images and anything a custom node
 * can do would cross the internet in the clear.
 */
object AddressPolicy {
    enum class Verdict { OK_TLS, OK_PRIVATE, NEEDS_OPT_IN, INVALID }

    fun check(url: String): Verdict {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return Verdict.INVALID
        val host = uri.host?.lowercase() ?: return Verdict.INVALID
        return when (uri.scheme?.lowercase()) {
            "https" -> Verdict.OK_TLS
            "http" -> if (isPrivateHost(host)) Verdict.OK_PRIVATE else Verdict.NEEDS_OPT_IN
            else -> Verdict.INVALID
        }
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")) return true
        if (h.endsWith(".ts.net")) return true // tailnet MagicDNS names resolve only inside the tailnet
        val v4 = h.split('.').mapNotNull { it.toIntOrNull() }
        if (v4.size == 4 && h.count { it == '.' } == 3) {
            val (a, b) = v4
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if (':' in h) return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
        return false
    }

    /** Normalizes what people type: adds a scheme, drops a trailing slash. */
    fun normalize(input: String): String {
        val t = input.trim().trimEnd('/')
        return if ("://" in t) t else "http://$t"
    }
}
