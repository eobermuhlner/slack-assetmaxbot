package ch.obermuhlner.slack.simplebot.impl

import ch.obermuhlner.slack.simplebot.PropertiesTranslationService
import ch.obermuhlner.slack.simplebot.TranslationService.Translation
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Before
import java.util.*

class PropertiesTranslationServiceImplTest {

    lateinit var service : PropertiesTranslationService

    @Before
    fun setup() {
        service = PropertiesTranslationServiceImpl()

        val englishProperties = Properties()
        val germanProperties = Properties()

        englishProperties.put("1", "one")
        germanProperties.put("1", "eins")
        englishProperties.put("2", "two")
        germanProperties.put("2", "zwei")

        service.parse(englishProperties, germanProperties)
    }

    @Test
    fun test_translations() {
        assertEquals(2, service.translations.size)
        assertEquals(true, service.translations.contains(Translation("one", "eins")))
        assertEquals(true, service.translations.contains(Translation("two", "zwei")))
    }

    @Test
    fun test_translations_multiple_parse() {
        assertEquals(2, service.translations.size)

        val englishProperties = Properties()
        val germanProperties = Properties()
        englishProperties.put("3", "three")
        germanProperties.put("3", "drei")
        service.parse(englishProperties, germanProperties)

        assertEquals(3, service.translations.size)
        assertEquals(true, service.translations.contains(Translation("one", "eins")))
        assertEquals(true, service.translations.contains(Translation("two", "zwei")))
        assertEquals(true, service.translations.contains(Translation("three", "drei")))
    }

    @Test
    fun test_clear() {
        assertEquals(2, service.translations.size)

        service.clear()

        assertEquals(0, service.translations.size)
    }
}