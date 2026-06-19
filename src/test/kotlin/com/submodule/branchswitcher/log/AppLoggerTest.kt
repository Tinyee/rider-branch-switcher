package com.submodule.branchswitcher.log

import com.submodule.branchswitcher.git.GitOps
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class AppLoggerTest {

    private val projectRoot: Path = Files.createTempDirectory("test-logger")

    @Before
    fun setup() {
        val dir = projectRoot.toFile()
        dir.mkdirs()
        val proc = ProcessBuilder("git", "init")
            .directory(dir)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.transferTo(java.io.OutputStream.nullOutputStream())
        val exit = proc.waitFor()
        assertTrue("git init must succeed in ${dir.absolutePath}", exit == 0)
    }

    @Test
    fun `git repo is created in setup`() {
        val dir = projectRoot.toFile()
        val dotGit = File(dir, ".git")
        assertTrue("projectRoot should exist: $dir", dir.exists())
        assertTrue(".git should exist: $dotGit", dotGit.exists())
        assertTrue(".git should be directory: $dotGit", dotGit.isDirectory)
        assertTrue("isGitRepo should return true", GitOps(timeoutSeconds = 10).isGitRepo(dir))
    }
}
