package com.submodule.branchswitcher

import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchExecutionResult

/** Test helper: wraps old-style (preset, options) calls into explicit ResolvedSwitchRequest. */
fun SwitchExecutor.executeTest(preset: Preset, options: SwitchOptions): Boolean =
    executeResultTest(preset, options).ok

fun SwitchExecutor.executeResultTest(preset: Preset, options: SwitchOptions): SwitchExecutionResult =
    execute(ResolvedSwitchRequest.resolve(preset, options))
