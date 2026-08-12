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
}
