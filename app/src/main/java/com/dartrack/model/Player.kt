package com.dartrack.model

import kotlinx.serialization.Serializable

/**
 * A registered player in the player registry. Identified by a stable [id]
 * (UUID) so that games can reference a player independently of their display
 * [name]. Going forward every game seat is backed by one of these.
 */
@Serializable
data class Player(val id: String, val name: String)
