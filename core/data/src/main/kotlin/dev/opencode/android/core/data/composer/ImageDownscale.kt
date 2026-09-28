package dev.opencode.android.core.data.composer

import kotlin.math.max

/**
 * Sizing a picture from the phone so the server accepts it.
 *
 * The server's defaults for an image attachment are a 2000 px edge and a 5 MiB Base64 budget, with
 * a 20 MiB decoded cap on anything (features doc §6, §20; the `media.image` config). A modern phone
 * camera produces far more than 2000 px, so something has to give: the phone, not the server.
 *
 * **Both the geometry and the size rule live here, and only the encoder does not.** [plan] takes the
 * size of the *encoded* result as a function, because a bitmap's compressed size cannot be computed
 * from its dimensions — that is the one part of the pipeline that needs the platform. Everything
 * else, including the decision to halve again, is a pure function of the two numbers and a
 * predicate, which is why this can be tested over a thousand cases instead of photographed.
 */
object ImageDownscale {

    /** The server's default longest edge for an image attachment. */
    const val MAX_EDGE_PX: Int = 2000

    /** The server's default Base64 budget, which is the encoded size and not the decoded one. */
    const val MAX_BASE64_BYTES: Long = 5L * 1024 * 1024

    /** Nothing is sent below this edge, however large the encoding turns out to be. */
    const val MIN_EDGE_PX: Int = 16

    /**
     * The dimensions to encode at.
     *
     * [encodedBytes] is called with candidate dimensions and must return the Base64 length the
     * platform encoder would produce; it is the only injected behaviour. The result is:
     *
     *  - **never upscaled.** A 400 px screenshot sent as a 2000 px image costs tokens and adds
     *    nothing, and a model's behaviour on an upscaled image is not better than on the original.
     *  - **fitted to the edge** first, preserving the aspect ratio, and
     *  - **halved** until the encoding fits the budget, at most down to [MIN_EDGE_PX]. An image that
     *    does not fit even at the floor is reported as such by [fits] rather than silently sent at
     *    a size the server will reject.
     */
    fun plan(
        width: Int,
        height: Int,
        encodedBytes: (Int, Int) -> Long,
        maxEdge: Int = MAX_EDGE_PX,
        budget: Long = MAX_BASE64_BYTES,
    ): Plan {
        require(width > 0 && height > 0) { "an image has a size; $width x $height is not one" }
        var w = width
        var h = height
        var steps = 0
        if (max(w, h) > maxEdge) {
            val scale = maxEdge.toDouble() / max(w, h).toDouble()
            w = scaled(w, scale).coerceAtLeast(1)
            h = scaled(h, scale).coerceAtLeast(1)
            steps = 1
        }
        var guard = 0
        while (encodedBytes(w, h) > budget && (w > MIN_EDGE_PX || h > MIN_EDGE_PX) && guard < MAX_STEPS) {
            w = (w + 1) / 2
            h = (h + 1) / 2
            steps++
            guard++
        }
        return Plan(width = w, height = h, steps = steps, fits = encodedBytes(w, h) <= budget)
    }

    /** How many halvings can possibly happen before the floor, which bounds the loop. */
    private const val MAX_STEPS = 24

    /**
     * The result of [plan].
     *
     * [steps] counts how many times the dimensions were reduced, `0` meaning the original was used
     * as it was, and [fits] says whether the encoding ended up inside the budget.
     */
    data class Plan(
        val width: Int,
        val height: Int,
        val steps: Int,
        val fits: Boolean,
    ) {
        /** Whether anything was done to the image at all. */
        val scaled: Boolean get() = steps > 0

        /**
         * The next plan down, for when the estimate was optimistic.
         *
         * [plan] is computed from an *estimated* encoded size, because the real size is whatever the
         * platform encoder produces and that is only known after encoding. A caller that encodes and
         * finds the result over budget halves with this and encodes again — which is why it is a
         * function of the plan rather than a number the caller has to work out.
         */
        fun halved(): Plan = copy(
            width = ((width + 1) / 2).coerceAtLeast(1),
            height = ((height + 1) / 2).coerceAtLeast(1),
            steps = steps + 1,
            fits = false,
        )
    }

    private fun scaled(value: Int, scale: Double): Int = Math.round(value * scale).toInt()

    /**
     * The Base64 length of [bytes].
     *
     * Base64 is four characters per three bytes with padding, so the length is derivable, and
     * knowing it up front is what lets the caller check a budget without encoding first.
     */
    fun base64Length(bytes: Long): Long = (bytes + 2) / 3 * 4
}
