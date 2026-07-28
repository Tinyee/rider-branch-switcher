# Documentation

Each subject has one source of truth. Link to the owning document instead of
copying version numbers, command matrices, architecture rules, or completed
work into another file.

## Current Documents

| Document | Owns |
| --- | --- |
| [`../README.md`](../README.md) | User-facing features, installation, quick start, settings, and screenshots |
| [`../README.zh-CN.md`](../README.zh-CN.md) | Chinese translation of the user README |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Module boundaries, package ownership, switch lifecycle, and change placement |
| [`ARCHITECTURE.zh-CN.md`](ARCHITECTURE.zh-CN.md) | Chinese architecture overview, reading order, and Kotlin syntax guide |
| [`SETUP.md`](SETUP.md) | JDK, platform SDK, local sandbox, and compatibility configuration |
| [`../CONTRIBUTING.md`](../CONTRIBUTING.md) | Contributor workflow, validation matrix, review rules, and commit checklist |
| [`ROADMAP.md`](ROADMAP.md) | Future priorities and explicit non-goals |
| [`../CHANGELOG.md`](../CHANGELOG.md) | Versioned delivered changes |
| [`encoding-and-line-endings.md`](encoding-and-line-endings.md) | Repository text-format policy |

The English and Chinese READMEs intentionally mirror each other. The Chinese
architecture guide is an onboarding companion rather than a second normative
architecture specification. Other current documents should summarize another
subject only when a reader needs orientation and should link to its owner for
details.

## Historical Decisions

[`review-history.md`](review-history.md) records durable outcomes from completed
code, test, architecture, and UI reviews. It explains why decisions were made
but does not redefine current behavior.

Superseded plans and detailed review drafts remain available in Git history;
they are not kept as parallel sources of current project facts.
