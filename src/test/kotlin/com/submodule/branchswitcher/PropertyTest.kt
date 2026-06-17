package com.submodule.branchswitcher

import com.google.gson.Gson
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.PresetFile
import com.submodule.branchswitcher.model.PresetOverrides
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.*
import io.kotest.property.forAll

/**
 * Property-based tests using Kotest. Each test generates random inputs and
 * verifies invariants that must hold for all valid inputs, not just hand-picked examples.
 */
class PropertyTest : StringSpec({

    // ── Preset serialization ──────────────────────────────────────

    "Preset JSON round-trip preserves all fields" {
        val presetArb = Arb.bind(
            Arb.string(1..20),
            Arb.string(1..20),
            Arb.list(Arb.string(1..10), 0..8),
            Arb.list(Arb.string(1..20), 0..8),
            Arb.boolean(),
        ) { name, main, paths, branches, pull ->
            val size = minOf(paths.size, branches.size)
            val subs = (0 until size).associate { paths[it] to branches[it] }
            Preset(name, main, subs.filterKeys { it.isNotEmpty() }, overrides = if (pull) null else PresetOverrides(pull = false))
        }

        forAll(presetArb) { preset ->
            val json = Gson().toJson(PresetFile(listOf(preset)))
            val restored = Gson().fromJson(json, PresetFile::class.java)
            val p = restored.presets.single()
            p.name == preset.name &&
            p.main == preset.main &&
            p.submodules == preset.submodules &&
            p.overrides == preset.overrides
        }
    }

    "PresetFile round-trip with multiple presets" {
        val presetListArb = Arb.list(
            Arb.bind(
                Arb.string(1..15), Arb.string(1..15), Arb.boolean()
            ) { name, main, pull ->
                Preset(name, main, overrides = if (pull) null else PresetOverrides(pull = false))
            },
            0..10,
        )

        forAll(presetListArb) { presets ->
            val json = Gson().toJson(PresetFile(presets))
            val restored = Gson().fromJson(json, PresetFile::class.java)
            restored.presets.size == presets.size &&
            restored.presets.zip(presets).all { (a, b) ->
                a.name == b.name && a.main == b.main && a.overrides == b.overrides
            }
        }
    }

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
        // Generate a valid .gitmodules fragment + noise
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
                // Create submodule directories so canonical resolution succeeds (required on Windows)
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

    // ── GitResult invariants ──────────────────────────────────────

    "GitResult.ok matches exitCode == 0" {
        forAll(
            Arb.string(0..30),   // cmd
            Arb.int(-1..128),    // exitCode
            Arb.string(0..50),   // stdout
            Arb.string(0..50),   // stderr
        ) { cmd, exitCode, stdout, stderr ->
            val r = com.submodule.branchswitcher.git.GitResult(cmd, exitCode, stdout, stderr)
            r.ok == (exitCode == 0)
        }
    }

    // ── PreflightRow computable properties ────────────────────────

    "PreflightRow invariants hold for any field combination" {
        forAll(
            Arb.string(1..20),   // label
            Arb.string(1..10),   // path
            Arb.string(0..20),   // target
            Arb.boolean(),       // exists
            Arb.string(0..20).orNull(),  // current
            Arb.int(-1..100),    // dirtyCount
            Arb.boolean(),       // hasLocal
            Arb.boolean(),       // hasRemote
        ) { label, path, target, exists, current, dirtyCount, hasLocal, hasRemote ->
            val row = com.submodule.branchswitcher.model.PreflightRow(
                label, path, target, exists, current, dirtyCount, hasLocal, hasRemote,
            )
            // invariants
            (row.isMain == (path == ".")) &&
            (!exists || row.needsSwitch == (current != target)) &&
            (!exists || row.branchMissing == (!hasLocal && !hasRemote))
        }
    }
})
