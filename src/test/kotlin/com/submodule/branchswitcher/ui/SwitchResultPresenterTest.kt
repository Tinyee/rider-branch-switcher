package com.submodule.branchswitcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchResultPresenterTest {
    @Test
    fun `notification details separate lock lines from retained state`() {
        assertEquals(
            "locked repository\n1 stash backup retained",
            joinNotificationDetails("locked repository", "1 stash backup retained"),
        )
    }

    @Test
    fun `notification details omit empty sections`() {
        assertEquals("retained state", joinNotificationDetails("", "retained state"))
    }

    @Test
    fun `notification detail is space-separated from a punctuation-free base`() {
        assertEquals(
            "所有仓库已恢复到切换前状态 主仓库 被残留的 git index.lock 阻塞",
            appendNotificationDetail("所有仓库已恢复到切换前状态", "主仓库 被残留的 git index.lock 阻塞"),
        )
    }

    @Test
    fun `notification detail is omitted when empty`() {
        assertEquals("base", appendNotificationDetail("base", ""))
    }
}
