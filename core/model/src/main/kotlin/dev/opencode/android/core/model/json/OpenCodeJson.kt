package dev.opencode.android.core.model.json

import kotlinx.serialization.json.Json

/**
 * The one [Json] configuration used for every OpenCode payload.
 *
 * - `ignoreUnknownKeys`: 2.0.x releases add fields often; unknown fields must never break decoding.
 *   The fixture contract tests re-encode every decoded fixture and fail on any field the models
 *   drop, so additions are noticed without breaking users.
 * - `explicitNulls = false`: absent optional fields decode to `null` and are omitted on encode,
 *   matching the server's `field?: T` convention.
 * - Unions are decoded by [DiscriminatedUnionSerializer] (with an `Unknown` fallback), not by
 *   kotlinx's built-in polymorphism, so no class discriminator is configured here.
 */
val OpenCodeJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    isLenient = false
    coerceInputValues = false
    allowSpecialFloatingPointValues = false
}
