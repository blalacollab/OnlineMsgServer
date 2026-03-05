package com.onlinemsg.client.data.preferences

import java.net.URI

object ServerUrlFormatter {
    const val maxServerUrls: Int = 8
    const val defaultServerUrl: String = "ws://10.0.2.2:13173/"

    fun normalize(input: String): String {
        var value = input.trim()
        if (value.isEmpty()) return ""

        if (!value.contains("://")) {
            value = "ws://$value"
        }

        if (value.startsWith("http://", ignoreCase = true)) {
            value = "ws://${value.substring("http://".length)}"
        } else if (value.startsWith("https://", ignoreCase = true)) {
            value = "wss://${value.substring("https://".length)}"
        }

        if (!value.endsWith('/')) {
            value += "/"
        }

        return try {
            val uri = URI(value)
            if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
                ""
            } else {
                value
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun dedupe(urls: List<String>): List<String> {
        val result = LinkedHashSet<String>()
        urls.forEach { raw ->
            val normalized = normalize(raw)
            if (normalized.isNotBlank()) {
                result += normalized
            }
        }
        return result.toList()
    }

    fun append(current: List<String>, rawUrl: String): List<String> {
        val normalized = normalize(rawUrl)
        if (normalized.isBlank()) return current
        val merged = listOf(normalized) + current.filterNot { it == normalized }
        return merged.take(maxServerUrls)
    }

    fun toggleWsProtocol(rawUrl: String): String {
        val normalized = normalize(rawUrl)
        if (normalized.isBlank()) return ""
        return try {
            val uri = URI(normalized)
            if (uri.scheme.equals("ws", ignoreCase = true)) {
                URI(
                    "wss",
                    uri.userInfo,
                    uri.host,
                    uri.port,
                    uri.path,
                    uri.query,
                    uri.fragment
                ).toString()
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        }
    }
}
