package com.submodule.branchswitcher.switch

/**
 * The one cancellation type core recognizes: thrown when the user cancelled the operation.
 *
 * Platform adapters convert their own cancellation types into this (an IntelliJ
 * [ProcessCanceledException] becomes an [OperationCancelledException] at the
 * [OperationControl] boundary), and git reads that were cancelled mid-run
 * ([GitResult.failureKind] CANCELLED/INTERRUPTED) are converted into it at the git read
 * boundary. A timeout is termination but NOT a user cancel, so it stays a git failure.
 *
 * Catch sites that decide "is this a user cancel" test `e is OperationCancelledException`
 * — never a classifier — and cancellation always propagates.
 */
class OperationCancelledException(
    message: String = "operation cancelled",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
