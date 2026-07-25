package com.submodule.branchswitcher

import com.intellij.util.messages.Topic
import com.submodule.branchswitcher.log.LogEntry

/** Posted when any switch operation completes (from tool window, action, or shortcut). */
interface BranchSwitchListener {
    companion object {
        val TOPIC = Topic.create("Branch Switcher Event", BranchSwitchListener::class.java)
    }
    fun onBranchSwitched()
    fun onLog(entry: LogEntry) {}
}
