package com.submodule.branchswitcher

import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.switch.SwitchExecutor

/** Test helper: wrap old-style (preset, options) calls for SwitchExecutor.execute(). */
fun SwitchExecutor.executeTest(preset: Preset, options: SwitchOptions): Boolean =
    execute(ResolvedSwitchRequest.resolve(preset, options))
