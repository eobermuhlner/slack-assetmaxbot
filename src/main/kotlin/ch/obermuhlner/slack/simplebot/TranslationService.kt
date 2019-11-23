package ch.obermuhlner.slack.simplebot

interface TranslationService {

    val translations: Set<Translation> get

    fun find(text: String): Map<String, Set<String>>?
    fun findPartial(text: String): List<Map<String, Set<String>>>

    data class Translation(
            val language: String,
            val key: String,
            val text: String)

}