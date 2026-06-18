# Encoding And Line Endings

This repository stores text files as UTF-8. Git normalizes text files to LF in the index; Windows-only scripts use CRLF.

## Rules

- Use UTF-8 for Markdown, Kotlin, XML, properties, Gradle, YAML, and shell scripts.
- Use LF for normal text files.
- Use CRLF only for Windows command scripts (`*.bat`, `*.cmd`, `*.ps1`).
- Do not rewrite the whole repository just to normalize existing mixed working-tree line endings. Keep diffs scoped to the files being changed.

## Windows PowerShell Notes

Some terminals may render UTF-8 Chinese text as mojibake even when the file is valid. Treat that as a terminal display problem, not file corruption.

Preferred checks:

```powershell
git diff --check
git ls-files --eol
rg -n "pattern" docs AGENTS.md CLAUDE.md
```

For readable UTF-8 output in PowerShell:

```powershell
chcp 65001
$OutputEncoding = [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new()
Get-Content -Encoding UTF8 AGENTS.md
```

For Python helpers:

```powershell
$env:PYTHONUTF8 = "1"
python -c "from pathlib import Path; print(Path('AGENTS.md').read_text(encoding='utf-8')[:200])"
```

## Review Guidance

- If only terminal output is mojibake, do not edit the document.
- If `git diff --check` passes and the file renders correctly in an editor, record the issue as terminal encoding only.
- When adding project rules, prefer ASCII for code examples and commands, but Chinese prose is fine in Markdown files.
