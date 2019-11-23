package ch.obermuhlner.slack.simplebot.impl

import ch.obermuhlner.slack.simplebot.PropertiesTranslationService
import ch.obermuhlner.slack.simplebot.TranslationService.Translation
import java.util.*

class PropertiesTranslationServiceImpl : PropertiesTranslationService {

	private val _translations = mutableSetOf<Translation>()

	private val mappedTranslations = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
	
	override val translations get() = _translations

	override fun clear() {
		_translations.clear()
	}

	override fun parse(language: String, translations: Properties) {
		for(key in translations.keys) {
			if(key is String) {
				val text = translations.getProperty(key)
				_translations.add(Translation(language, key, text))
			}
		}
	}

	override fun buildIndex() {
		val keyToTranslations = mutableMapOf<String, MutableMap<String, String>>()
		for (translation in translations) {
			val langToText = keyToTranslations.getOrPut(translation.key) { mutableMapOf() }
			langToText[translation.language] = translation.text
		}

		mappedTranslations.clear()
		for (keyEntry in keyToTranslations.entries) {
			val masterText = keyEntry.value["en"]
			for (sourceLanguageEntry in keyEntry.value.entries) {
				val sourceLanguage = sourceLanguageEntry.key
				val sourceText = sourceLanguageEntry.value
				if (sourceLanguage == "en" || sourceText != masterText) {
					val languageToTexts = mappedTranslations.getOrPut(sourceText.toLowerCase()) { mutableMapOf() }
					for (targetLanguageEntry in keyEntry.value.entries) {
						val targetLanguage = targetLanguageEntry.key
						val targetText = targetLanguageEntry.value
						if (targetLanguage == "en" || targetText != masterText) {
							val texts = languageToTexts.getOrPut(targetLanguage) { mutableSetOf() }
							texts += targetText
						}
					}
				}
			}
		}
	}

	override fun find(text: String): Map<String, Set<String>>? {
		return mappedTranslations[text]
	}

	override fun findPartial(text: String): List<Map<String, Set<String>>> {
		val matches = mutableListOf<Map<String, Set<String>>>()
		for (entry in mappedTranslations.entries) {
			if (entry.key.contains(text)) {
				matches += entry.value
			}
		}
		return matches
	}
}