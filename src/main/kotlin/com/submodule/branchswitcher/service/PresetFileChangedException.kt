package com.submodule.branchswitcher.service

import java.nio.file.Path

/**
 * Thrown when a save would silently overwrite external edits made to the preset
 * file since it was loaded. The UI should prompt the user to reload instead of
 * clobbering the on-disk version.
 */
class PresetFileChangedException(val file: Path) :
    Exception("preset file changed on disk since it was loaded: $file")
