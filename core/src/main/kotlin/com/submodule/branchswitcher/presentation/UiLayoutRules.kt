package com.submodule.branchswitcher.presentation

fun shouldShowSecondaryAction(availableWidth: Int, requiredWidth: Int): Boolean =
    availableWidth <= 0 || availableWidth >= requiredWidth
