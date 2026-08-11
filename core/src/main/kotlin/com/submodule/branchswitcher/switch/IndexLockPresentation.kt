package com.submodule.branchswitcher.switch

/** Locale-neutral, structured presentation of one stale-index.lock block for a notification. */
data class LockBlockedPresentation(
    val repositoryLabel: String,
    val lockPath: String,
)

/**
 * Maps every [INDEX_LOCK_BLOCKING] issue in [issues] to a locale-neutral presentation.
 *
 * The main repository ("." or an absent path) is rendered with [mainRepositoryLabel];
 * submodules keep their repository path. The exact lock file path is carried so the
 * UI can localize the actionable message without parsing an English diagnostic.
 */
fun lockBlockedPresentations(
    issues: List<OperationIssue>,
    mainRepositoryLabel: String,
): List<LockBlockedPresentation> =
    issues.filter { it.code == OperationIssueCode.INDEX_LOCK_BLOCKING }
        .map { issue ->
            LockBlockedPresentation(
                repositoryLabel = if (issue.repositoryPath.isNullOrBlank() || issue.repositoryPath == ".") {
                    mainRepositoryLabel
                } else {
                    issue.repositoryPath
                },
                lockPath = issue.lockPath.orEmpty(),
            )
        }
