package com.submodule.branchswitcher.switch

/** After main repo checkout, run `git submodule sync --recursive` to align .gitmodules URLs. */
class SubmoduleSyncStep : SwitchStep {
    override val name = "submodule sync"

    override fun execute(context: SwitchContext): StepResult {
        val dir = context.projectRoot.toFile()
        val s = context.git.submoduleSync(dir)
        if (s.ok) {
            context.log("submodule sync ok")
        } else {
            context.log("[warn] submodule sync failed: ${s.stderr.lines().firstOrNull() ?: ""}")
        }
        return StepResult.Success
    }
}
