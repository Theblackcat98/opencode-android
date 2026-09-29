package dev.opencode.android.feature.admin

/**
 * The tags the Phase 9 screens carry, so a test addresses a row by its identity and not by its
 * position.
 *
 * **Derived from the thing a row names, never from an index.** The explorer rebuilds on every
 * `config.updated` and the document list is in the server's own precedence order, so a test that
 * addressed `items[3]` would silently start testing a different key the moment the order changed —
 * which is exactly the kind of test that goes green without testing anything.
 */
object AdminTags {
    /** One top-level configuration key's section, by its file name. */
    fun configKey(key: String): String = "config:key:$key"

    /** The shell field. */
    const val SHELL_FIELD: String = "config:shell"

    /** Confirms a pending write, for whichever screen raised it. */
    const val CONFIRM_WRITE: String = "config:confirm-write"

    /** Cancels a pending write. */
    const val CANCEL_WRITE: String = "config:cancel-write"

    /**
     * The explorer's list itself, so a test can scroll it by index.
     *
     * **Needed because the list is lazy and the claim is about every key.** A card that composes is not
     * the same as a card a user can reach, and only scrolling proves the second; the list is in the
     * schema's own order, so index *n* is the *n*-th key and a test can walk all thirty-six.
     */
    const val CONFIG_LIST: String = "config:list"

    /** The configuration text field. */
    const val CONFIG_EDITOR: String = "config:editor"

    /** One guided template's trigger. */
    fun template(choice: ConfigTemplateChoice): String = "config:template:${choice.id}"

    /** One diagnostic line, by the instance path it points at. */
    fun diagnostic(path: String): String = "config:diagnostic:$path"

    /** One saved approval's row, by its id. */
    fun savedPermission(id: String): String = "permissions:saved:$id"

    /** Removes a saved approval, which is confirmed first. */
    fun removeSaved(id: String): String = "permissions:remove-saved:$id"

    /** Confirms removing a saved approval. */
    const val CONFIRM_REMOVE_SAVED: String = "permissions:confirm-remove-saved"

    /** The permission-rules editor's precedence warning, which is not dismissible. */
    const val RULES_PRECEDENCE_WARNING: String = "permissions:precedence-warning"

    /** One session permission rule's row, by its position among the *server's* rules. */
    fun rule(index: Int): String = "permissions:rule:$index"

    /** Adds a rule to the editor. */
    const val ADD_RULE: String = "permissions:add-rule"

    /** Saves the edited rules. */
    const val SAVE_RULES: String = "permissions:save-rules"

    /** One definition file's kind picker entry. */
    fun definition(kind: dev.opencode.android.core.data.config.DefinitionKind): String = "definition:kind:${kind.id}"

    /** The definition's name field. */
    const val DEFINITION_NAME: String = "definition:name"

    /** The definition's body field. */
    const val DEFINITION_BODY: String = "definition:body"

    /** One front-matter field, by its key. */
    fun frontMatter(key: String): String = "definition:frontmatter:$key"

    /** One loaded location's row, by its directory. */
    fun location(directory: String): String = "maintenance:location:$directory"

    /** Evicts a loaded location, which is confirmed first. */
    fun evict(directory: String): String = "maintenance:evict:$directory"

    /** Confirms an eviction. */
    const val CONFIRM_EVICT: String = "maintenance:confirm-evict"

    /** Reloads every loaded location. */
    const val RELOAD_LOCATIONS: String = "maintenance:reload"

    /** One instruction entry's row, by its key. */
    fun instruction(key: String): String = "instructions:entry:$key"

    /** The instruction entry's key field. */
    const val INSTRUCTION_KEY: String = "instructions:key"

    /** The instruction entry's value field. */
    const val INSTRUCTION_VALUE: String = "instructions:value"

    /** Puts the entry being edited. */
    const val PUT_INSTRUCTION: String = "instructions:put"

    /** Confirms removing an entry. */
    const val CONFIRM_REMOVE_INSTRUCTION: String = "instructions:confirm-remove"

    /** One agent's row, by its name. */
    fun agent(name: String): String = "catalog:agent:$name"

    /** One command's row, by its name. */
    fun command(name: String): String = "catalog:command:$name"

    /** One skill's row, by its name. */
    fun skill(name: String): String = "catalog:skill:$name"

    /** One reference's row, by its name. */
    fun reference(name: String): String = "catalog:reference:$name"

    /** Opens the tab for one catalog. */
    fun tab(id: String): String = "catalog:tab:$id"
}
