package com.kemzy.liveavatar.security

sealed interface UnlockState {
    data object NeedsSetup : UnlockState
    data object Locked : UnlockState
    data object Unlocked : UnlockState
}
