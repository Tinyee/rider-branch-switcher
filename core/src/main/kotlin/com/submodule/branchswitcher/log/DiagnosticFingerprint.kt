package com.submodule.branchswitcher.log

import java.security.MessageDigest

/** Stable, non-reversible identifier for sensitive diagnostic values such as remote URLs. */
fun diagnosticFingerprint(value: String?): String {
    if (value == null) return "none"
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { byte -> "%02x".format(byte) }
}
