package com.submodule.branchswitcher

import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.forAll

/**
 * Property-based tests using Kotest. Each test generates random inputs and
 * verifies invariants that must hold for all valid inputs, not just hand-picked examples.
 */
class PropertyTest : StringSpec({

    // ── .gitmodules parser robustness ─────────────────────────────

    "listSubmodulePaths never crashes on arbitrary text" {
        forAll(Arb.list(Arb.string(0..80), 0..50)) { lines ->
            val content = lines.joinToString("\n")
            val dir = java.nio.file.Files.createTempDirectory("gm-")
            try {
                java.nio.file.Files.writeString(dir.resolve(".gitmodules"), content)
                val ops = com.submodule.branchswitcher.git.GitOps(10)
                val paths = ops.listSubmodulePaths(dir.toFile())
                paths.all { it.isNotEmpty() }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    "listSubmodulePaths extracts valid path= lines" {
        // 生成合法的 .gitmodules 内容，验证 parser 能正确提取所有 path= 行。
        //
        // 设计说明：
        // - [a-z0-9/_-] 仅用大小写无关、无尾部规范化问题的字符。
        //   Windows 对大小写、尾部 . 和 / 会做文件系统规范化，导致 canonicalFile
        //   把两条不同路径解析到同一位置，visited 集合将其视为重复而跳过。
        //   生成阶段避开这些差异，让测试聚焦于 parser 而非平台文件系统语义。
        // - 生产代码 collectSubmodulePaths 调用 File.canonicalFile 校验路径安全，
        //   需要子模块目录真实存在于磁盘上，否则 Windows 上 canonicalFile 会抛异常
        //   导致路径被静默跳过。因此测试中需要 createDirectories。
        // - 核心边界（SubA、深层嵌套、./、../、绝对路径等）由 GitOpsTest 覆盖，
        //   本测试是 property-based 补充，不重复那些用例。
        val pathStr = Arb.string(1..15)
            .filter { it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '/' || c == '-' || c == '_' } }
            .filter { it != "." && it != ".." && !it.startsWith("/") && !it.endsWith("/") && it.split("/").none { c -> c == ".." || c.isEmpty() } }
        val validPaths = Arb.list(pathStr, 1..10).filter { it.distinct().size == it.size }
        forAll(validPaths) { paths ->
            val content = buildString {
                paths.forEach { p ->
                    appendLine("[submodule \"$p\"]")
                    appendLine("    path = $p")
                    appendLine("    url = https://example.com/$p.git")
                }
            }
            val dir = java.nio.file.Files.createTempDirectory("gm-")
            try {
                java.nio.file.Files.writeString(dir.resolve(".gitmodules"), content)
                // 创建子模块目录：canonicalFile 在 Windows 上要求路径存在才能解析
                paths.forEach { p ->
                    java.nio.file.Files.createDirectories(dir.resolve(p))
                }
                val ops = com.submodule.branchswitcher.git.GitOps(10)
                val result = ops.listSubmodulePaths(dir.toFile())
                result == paths
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

})
