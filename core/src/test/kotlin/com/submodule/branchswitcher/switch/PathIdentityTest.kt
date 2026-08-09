package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitRepositoryQuery
import com.submodule.branchswitcher.git.RepositoryIdentity
import com.submodule.branchswitcher.git.SubmoduleRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PathIdentityTest {

    @Test
    fun `resolveGitDir accepts a missing nested submodule path`() {
        val root = Files.createTempDirectory("test-path-root").toFile()
        val resolved = resolveGitDir(root.toPath(), "a/b/c")
        assertEquals(File(root, "a/b/c"), resolved)
    }

    @Test
    fun `resolveGitDir rejects a path that escapes through a symlink`() {
        val base = Files.createTempDirectory("test-path-root")
        val outside = Files.createTempDirectory("test-path-outside")
        val link = base.resolve("escapes")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (e: Exception) {
            assumeTrue("symbolic links unsupported on this platform/filesystem: ${e.message}", false)
            return
        }
        val exception = assertThrows(IllegalArgumentException::class.java) {
            resolveGitDir(base, "escapes")
        }
        assertTrue(exception.message!!.contains("escapes project root"))
    }

    @Test
    fun `pathIdentity on an existing path equals toRealPath`() {
        val file = Files.createTempDirectory("test-path-real").toFile()
        assertEquals(file.toPath().toRealPath().toString(), file.pathIdentity())
    }

    @Test
    fun `pathIdentity on a missing path does not throw`() {
        val root = Files.createTempDirectory("test-path-missing").toFile()
        val missing = File(root, "does/not/exist")
        val identity = missing.pathIdentity()
        assertTrue(identity.endsWith("does/not/exist"))
    }

    @Test
    fun `expectedSubmoduleGitDirectory tolerates a not-yet-created git modules path`() {
        val root = Files.createTempDirectory("test-path-gitdir").toFile()
        val git = object : GitRepositoryQuery {
            override fun currentBranch(workDir: File): String? = null
            override fun revParseHead(workDir: File): String? = null
            override fun repositoryIdentity(workDir: File): RepositoryIdentity? =
                RepositoryIdentity(File(root, ".git").path, superprojectRoot = null)
        }
        val registration = SubmoduleRegistration(path = "SubA", sectionName = "SubA", parentPath = ".")
        val expected = expectedSubmoduleGitDirectory(root, registration, git)
        assertTrue(expected!!.endsWith("modules/SubA"))
    }
}
