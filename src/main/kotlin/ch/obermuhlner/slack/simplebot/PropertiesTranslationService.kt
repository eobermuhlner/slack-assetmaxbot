package ch.obermuhlner.slack.simplebot

import java.util.*

interface PropertiesTranslationService : TranslationService {

    fun clear()

    fun parse(language: String, translations: Properties)

    fun buildIndex()
}