package com.submodule.branchswitcher.log

/** Removes credentials and repository addresses before diagnostic text reaches persistent logs. */
fun sanitizeDiagnosticText(value: String): String {
    var sanitized = URI_REMOTE.replace(value) { match -> remotePlaceholder(match.value) }
    sanitized = SCP_REMOTE.replace(sanitized) { match -> remotePlaceholder(match.value) }
    return SECRET_ASSIGNMENT.replace(sanitized) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
    }
}

private fun remotePlaceholder(remote: String): String =
    "<remote:${diagnosticFingerprint(remote)}>"

private val URI_REMOTE = Regex(
    pattern = "(?i)\\b(?:https?|ssh|git|file)://[^\\s'\\\"<>]+",
)

private val SCP_REMOTE = Regex(
    pattern = "(?i)(?<![A-Za-z0-9._-])(?:[A-Za-z0-9._-]+@[A-Za-z0-9._-]+|" +
        "(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}):[^\\s'\\\"<>]+",
)

private val SECRET_ASSIGNMENT = Regex(
    pattern = "(?i)\\b(access[_-]?token|private[_-]?token|token|password|passwd|oauth2|" +
        "client[_-]?secret|authorization)(\\s*[=:]\\s*)(?:(?:basic|bearer)\\s+)?[^\\s,;]+",
)
