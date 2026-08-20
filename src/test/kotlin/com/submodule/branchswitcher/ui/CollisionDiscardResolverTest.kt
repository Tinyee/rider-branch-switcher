package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.model.PreflightRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Locks the approval-set derivation: .meta always discardable, other files only without the only-meta restriction. */
class CollisionDiscardResolverTest {

    private fun row(path: String, collisions: Set<String>) = PreflightRow(
        label = path,
        path = path,
        target = "dev",
        exists = true,
        current = "main",
        dirtyCount = collisions.size,
        hasLocal = true,
        hasRemote = true,
        untrackedCollisions = collisions,
    )

    @Test
    fun `all collisions are approved by default`() {
        val rows = listOf(row(".", setOf("Assets/Foo.meta", "src/dirty.kt")))

        val result = resolveCollisionDiscards(rows, onlyMeta = false)

        assertEquals(setOf("Assets/Foo.meta", "src/dirty.kt"), result["."])
    }

    @Test
    fun `only-meta keeps non-meta files`() {
        val rows = listOf(row(".", setOf("Assets/Foo.meta", "src/dirty.kt")))

        val result = resolveCollisionDiscards(rows, onlyMeta = true)

        assertEquals(setOf("Assets/Foo.meta"), result["."])
    }

    @Test
    fun `repos without collisions are omitted`() {
        val rows = listOf(
            row(".", emptySet()),
            row("SubA", setOf("Assets/Bar.meta")),
        )

        val result = resolveCollisionDiscards(rows, onlyMeta = false)

        assertEquals(mapOf("SubA" to setOf("Assets/Bar.meta")), result)
    }

    @Test
    fun `only-meta drops a repo whose collisions are all non-meta`() {
        val rows = listOf(row(".", setOf("src/dirty.kt")))

        val result = resolveCollisionDiscards(rows, onlyMeta = true)

        assertEquals(emptyMap<String, Set<String>>(), result)
    }
}
