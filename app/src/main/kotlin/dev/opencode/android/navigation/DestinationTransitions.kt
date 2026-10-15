package dev.opencode.android.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * The app's navigation transition language.
 *
 * This replaces the Navigation default (a 700ms cross-fade on every destination), which
 * T-001 names as a finding: 700ms exceeds Material's own maximum for a screen transition,
 * and a cross-fade holds both screens at partial opacity while it runs. Every destination
 * now declares which relationship it has to the one it replaces, and the transition states
 * that relationship (T-011):
 *
 * - **Top level** (the server picker and the three bottom-bar tabs): cross-fade. These are
 *   unordered peers — system back returns to the start destination regardless of history, so
 *   there is no direction to promise.
 * - **Forward** (everything pushed above a top-level screen): slide in from the right with a
 *   fade beside it; back plays the inverse (T-024). The push exit is effects-only (T-010):
 *   something leaving should not hold attention on its way out.
 * - **Container** (session to review, session to files): the destination scales up from 92%
 *   with a fade — the container-transform language, anchored to its origin (T-016). A true
 *   shared-element transition would need `SharedTransitionLayout` wiring in both screens;
 *   until that exists this anchored zoom is the deliberate stand-in, recorded in `docs/MOTION.md`.
 *
 * The durations are the M3 emphasized pair (400ms enter, 200ms exit). The literal `tween()`
 * calls live here, in the token definitions, which is where T-002 permits them: no call site
 * names a duration or a spec of its own.
 */
data class DestinationTransitions(
    val enter: EnterTransition,
    val exit: ExitTransition,
    val popEnter: EnterTransition,
    val popExit: ExitTransition,
) {
    companion object {
        /** Unordered top-level destinations: cross-fade, no direction promised. */
        val TopLevel = DestinationTransitions(
            enter = fadeIn(tween(ENTER_MS)),
            exit = fadeOut(tween(EXIT_MS)),
            popEnter = fadeIn(tween(ENTER_MS)),
            popExit = fadeOut(tween(EXIT_MS)),
        )

        /** Parent to child: spatial in with effects beside it, effects-only out, inverse on back. */
        val Forward = DestinationTransitions(
            enter = slideInHorizontally(tween(ENTER_MS)) { it } + fadeIn(tween(ENTER_MS)),
            exit = fadeOut(tween(EXIT_MS)),
            popEnter = fadeIn(tween(ENTER_MS)),
            popExit = slideOutHorizontally(tween(ENTER_MS)) { it } + fadeOut(tween(EXIT_MS)),
        )

        /** One element becoming the next screen: anchored scale with a fade, reversed on back. */
        val Container = DestinationTransitions(
            enter = scaleIn(tween(ENTER_MS), initialScale = CONTAINER_FROM) + fadeIn(tween(ENTER_MS)),
            exit = fadeOut(tween(EXIT_MS)),
            popEnter = fadeIn(tween(ENTER_MS)),
            popExit = scaleOut(tween(ENTER_MS), targetScale = CONTAINER_FROM) + fadeOut(tween(EXIT_MS)),
        )
    }
}

private const val ENTER_MS = 400
private const val EXIT_MS = 200

/** The scale a container transition starts from (and returns to on back). */
private const val CONTAINER_FROM = 0.92f
