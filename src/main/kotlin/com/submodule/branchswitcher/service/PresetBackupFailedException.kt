package com.submodule.branchswitcher.service

import java.nio.file.Path

/**
 * Thrown when the recovery backup of a preset file that had invalid entries dropped
 * at load could not be written. The save is refused because overwriting the original
 * without a backup would permanently lose those entries; the user should resolve the
 * backup-write failure (e.g. permissions on the `.bak` target) and save again.
 */
class PresetBackupFailedException(
    /** The preset file whose overwrite would permanently lose the dropped entries. */
    val source: Path,
    /** The sibling `.bak` path that was intended to preserve the original bytes. */
    val backup: Path,
    cause: Throwable,
) : Exception("could not write the recovery backup of $source to $backup before overwriting it", cause)
