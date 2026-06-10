package com.submodule.branchswitcher.switch

/** After main repo checkout, run `git submodule sync --recursive` to align .gitmodules URLs. */
class SubmoduleSyncStep : SwitchStep {
    override val name = "submodule sync"

    override fun execute(context: SwitchContext): StepResult {
        // Only sync if main checkout succeeded — otherwise .gitmodules may reflect old branch
        if ("." !in context.successfulCheckouts) {
            context.log("[skip] submodule sync — main checkout did not succeed")
            return StepResult.Partial(mapOf("." to "sync skipped: main checkout failed"))
        }
        val dir = context.projectRoot.toFile()
        val s = context.git.submoduleSync(dir)
        if (s.ok) {
            context.log("submodule sync ok")
            return StepResult.Success
        } else {
            context.log("[warn] submodule sync failed: ${s.stderr.lines().firstOrNull() ?: ""}")
            return StepResult.Partial(mapOf("." to "submodule sync failed"))
        }
    }
}
