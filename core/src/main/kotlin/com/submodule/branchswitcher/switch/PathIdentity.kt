package com.submodule.branchswitcher.switch

import java.io.File
import java.nio.file.Path

/**
 * Canonical path identity reliable on Windows (symlinks, 8.3 short names).
 *
 * - existing path -> `toRealPath()` (resolves symlinks; the pattern already used by
 *   `com.submodule.branchswitcher.git.impl.GitCommandClient.resolvedPath` and proven on Windows CI)
 * - missing path  -> `canonicalPath` (best-effort normalization of the existing ancestors;
 *   `toRealPath()` would throw `NoSuchFileException`)
 * - any failure   -> `absolutePath` (never throws)
 *
 * Prefer over raw `File.canonicalFile` for identity/boundary checks, which is not
 * reliable for symlinks on Windows.
 */
fun File.pathIdentity(): String = try {
    if (exists()) toPath().toRealPath().toString() else canonicalPath
} catch (_: Exception) {
    absolutePath
}

fun Path.pathIdentity(): String = toFile().pathIdentity()

/**
 * Strict variant for safety boundaries: throws when the path cannot be resolved
 * (permission errors, damaged symlinks, unsupported operations) instead of
 * degrading to a lexical path. Use [pathIdentity] for display/logging, and this
 * for escape/boundary checks that must fail closed.
 */
fun File.resolvedIdentity(): String =
    if (exists()) toPath().toRealPath().toString() else canonicalPath
