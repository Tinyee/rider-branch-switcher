package com.submodule.branchswitcher

import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.switch.SwitchExecutor

/** Test helper: wraps old-style (preset, options) calls into explicit ResolvedSwitchRequest. */
fun SwitchExecutor.executeTest(preset: Preset, options: SwitchOptions): Boolean =
    execute(ResolvedSwitchRequest.resolve(preset, options))
