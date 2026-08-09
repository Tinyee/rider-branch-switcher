package com.submodule.branchswitcher.presentation

import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.ResolvedSwitchRequest

/** Warning condition for switching without stashing — pure rule, no UI dependency. */
fun shouldShowForceWarning(
    request: ResolvedSwitchRequest,
    rows: List<PreflightRow>,
): Boolean = request.options.dirty == DirtyAction.Force
    && rows.any { it.exists && it.dirtyCount != 0 }
