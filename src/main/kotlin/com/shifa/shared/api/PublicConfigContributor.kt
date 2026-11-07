package com.shifa.shared.api

/**
 * Adds top-level keys to `GET /api/public/config`. Features implement it so this shared controller never depends on
 * them; keys must not collide with each other or with the controller's own.
 */
fun interface PublicConfigContributor {
    fun publicConfig(): Map<String, Any?>
}
