/*
 * Copyright (C) 2026 Quik contributors
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.octoshrimpy.quik.manager

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionBackedReactionRuntimeAuthority @Inject constructor(
    private val permissionManager: PermissionManager,
) : ReactionRuntimeAuthority {
    override fun canSendReaction(): Boolean =
        permissionManager.isDefaultSms() && permissionManager.hasSendSms()
}
