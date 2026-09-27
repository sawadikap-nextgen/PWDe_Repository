package com.pwde.app.data.gabai

import com.pwde.app.data.model.Game

/**
 * The voice phrase "Use All Suggested Words" gives a button. Games can have their own short,
 * easy-to-say phrases (said mid-fight, so fewer syllables win); any other button falls back to its
 * label in lowercase.
 */
object SuggestedPhrases {
    fun forButton(gameId: String?, label: String): String {
        val key = normalize(label)
        val phrases = BY_GAME[gameId].orEmpty()
        return phrases[key] ?: key
    }

    /** "Skill_button 2" → "skill button 2"; "Skill 1" → "skill 1". */
    private fun normalize(label: String) = label.lowercase().replace('_', ' ').replace(Regex("\\s+"), " ").trim()

    /**
     * Mobile Legends: Bang Bang. Keys cover both detected labels ("Skill button 1", from
     * [detectedToButtons]) and names people type when placing buttons by hand ("Skill 1", "Ult").
     * Skill 3 is the ultimate for most heroes, so it gets its own word.
     */
    private val MOBILE_LEGENDS = buildMap {
        listOf("basic attack", "basic", "auto", "attack", "oto").forEach { put(it, "auto") }
        listOf("skill button 1", "skill 1", "skill one", "first skill", "s1").forEach { put(it, "first") }
        listOf("skill button 2", "skill 2", "skill two", "second skill", "s2").forEach { put(it, "second") }
        listOf("skill button 3", "skill 3", "skill three", "ultimate", "ult", "s3").forEach { put(it, "third") }
        listOf("skill button 4", "skill 4", "skill four", "s4").forEach { put(it, "fourth") }
        listOf("skill upgrade 1", "upgrade 1").forEach { put(it, "level one") }
        listOf("skill upgrade 2", "upgrade 2").forEach { put(it, "level two") }
        listOf("skill upgrade 3", "upgrade 3", "upgrade ultimate").forEach { put(it, "level three") }
        listOf("recall", "back", "go home").forEach { put(it, "recall") }
        listOf("regen", "heal", "restore").forEach { put(it, "regen") }
        listOf("spell", "battle spell", "flicker", "retribution").forEach { put(it, "spell") }
        listOf("buy item", "buy", "shop").forEach { put(it, "buy") }
        listOf("use item", "item", "active item").forEach { put(it, "use item") }
        listOf("lord", "tap lord").forEach { put(it, "target lord") }
        listOf("turtle", "tap turtle").forEach { put(it, "target turtle") }
        listOf("minion", "tap minion").forEach { put(it, "target minion") }
        listOf("tower", "turret").forEach { put(it, "target tower") }
        listOf("hero lock", "lock", "lock target").forEach { put(it, "lock on") }
        listOf("map", "minimap").forEach { put(it, "map") }
        listOf("chat", "quick chat").forEach { put(it, "chat") }
    }

    private val BY_GAME: Map<String, Map<String, String>> = mapOf(Game.MOBILE_LEGENDS.id to MOBILE_LEGENDS)
}
