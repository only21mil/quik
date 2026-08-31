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

/** Rechecks the Android role and permission required at each telephony boundary. */
interface ReactionRuntimeAuthority {
    fun canSendReaction(): Boolean
}
