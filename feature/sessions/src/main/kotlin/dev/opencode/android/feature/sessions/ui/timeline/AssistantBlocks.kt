package dev.opencode.android.feature.sessions.ui.timeline

import dev.opencode.android.core.model.AssistantContent

/** What an assistant message draws, in order: its own parts, with runs of quiet tool calls folded together. */
sealed interface AssistantBlock {
    /** A part drawn as itself: text, reasoning, an unknown part, or a tool call that is not folded. */
    data class Part(val part: AssistantContent, val card: ToolCard? = null) : AssistantBlock

    /** Two or more consecutive tool calls that all finished cleanly, drawn as one "Ran N tools" row. */
    data class Tools(val cards: List<ToolCard>) : AssistantBlock
}

/**
 * Splits [content] into blocks, folding every run of at least [minimum] consecutive *quiet* tool calls.
 *
 * Quiet means finished without an error and not a to-do list. **A failed call is never folded**: its reason has to
 * be on screen without opening anything, so it breaks a run and is drawn on its own, as is a call still running
 * (its progress is the news) and a to-do list (the list is the point of the call). Text and reasoning also break a
 * run, so the order of the message is never changed.
 */
fun assistantBlocks(content: List<AssistantContent>, minimum: Int = MIN_GROUP): List<AssistantBlock> {
    val blocks = mutableListOf<AssistantBlock>()
    val run = mutableListOf<Pair<AssistantContent.Tool, ToolCard>>()

    fun flush() {
        if (run.size >= minimum) {
            blocks += AssistantBlock.Tools(run.map { it.second })
        } else {
            run.forEach { (part, card) -> blocks += AssistantBlock.Part(part, card) }
        }
        run.clear()
    }

    content.forEach { part ->
        if (part is AssistantContent.Tool) {
            val card = part.toCard()
            if (card.isQuiet) {
                run += part to card
            } else {
                flush()
                blocks += AssistantBlock.Part(part, card)
            }
        } else {
            flush()
            blocks += AssistantBlock.Part(part)
        }
    }
    flush()
    return blocks
}

private val ToolCard.isQuiet: Boolean
    get() = status == ToolStatus.Completed && kind != ToolCardKind.TODO

/** Two calls are already worth a line of their own; one is just a row. */
const val MIN_GROUP = 2
