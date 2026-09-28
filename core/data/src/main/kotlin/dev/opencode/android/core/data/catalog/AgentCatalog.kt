package dev.opencode.android.core.data.catalog

import dev.opencode.android.core.model.AgentInfo

/**
 * The agent list as the picker and the composer's cycle button show it (features doc §7).
 *
 * **Only primary agents are offered for selection.** A `subagent` is invoked by a tool call, not by
 * a user choosing it, and a `hidden` agent is one the server has retired or does not want selected;
 * showing either would put a choice in front of the user that either cannot be made or should not
 * be. `mode = all` counts as primary, because that is what the schema says it means.
 *
 * Pure and unit tested, because "which agents may I pick" is a rule, not a layout decision.
 */
object AgentCatalog {

    /** The agents a user may select, in the order the server listed them. */
    fun primary(agents: List<AgentInfo>): List<AgentInfo> = agents.filter { it.isSelectable }

    /** True when this client may offer [agent] as a session's agent. */
    val AgentInfo.isSelectable: Boolean
        get() = !hidden && (isPrimary || mode == AgentInfo.AgentMode.ALL)

    /** The label a row shows: the name, with the id when the two differ. */
    fun label(agent: AgentInfo): String =
        if (agent.name.isNotBlank() && agent.name != agent.id) "${agent.name} (${agent.id})" else agent.id
}
