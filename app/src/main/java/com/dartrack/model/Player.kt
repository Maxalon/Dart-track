package com.dartrack.model

import kotlinx.serialization.Serializable

/**
 * A registered player in the player registry. Identified by a stable [id]
 * (UUID) so that games can reference a player independently of their display
 * [name]. Going forward every game seat is backed by one of these.
 *
 * [accountId] links the player to a server account (Dart-Track-Server user
 * id): the logged-in user's own "me" player, or a friend. Null for a purely
 * local player — and for every player in a players.json written before
 * accounts existed (the default keeps those decoding).
 *
 * [hidden] marks a player that only exists so a game synced from another
 * phone can name its seat: an account that took part in one of those games
 * but isn't this user's friend (e.g. a friend's other friend). Hidden players
 * never show up in pickers, player management or stats. Default false keeps
 * older players.json decoding (and omitted when encoding).
 */
@Serializable
data class Player(
    val id: String,
    val name: String,
    val accountId: String? = null,
    val hidden: Boolean = false,
)
