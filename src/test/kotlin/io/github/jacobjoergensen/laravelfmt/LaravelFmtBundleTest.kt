package io.github.jacobjoergensen.laravelfmt

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Properties

class LaravelFmtBundleTest {

    private val properties = Properties().apply {
        File("src/main/resources/messages/LaravelFmtBundle.properties").reader(Charsets.UTF_8).use(::load)
    }

    @Test
    fun `keys used in Kotlin sources exist`() {
        val usage = Regex("""LaravelFmtBundle\.message\(\s*"([^"]+)"""")
        val keys = File("src/main/kotlin").walk().filter { it.extension == "kt" }
            .flatMap { file -> usage.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()

        assertTrue(keys.isNotEmpty())
        assertTrue(properties.stringPropertyNames().containsAll(keys), "Missing: ${keys - properties.stringPropertyNames()}")
    }

    @Test
    fun `keys and action ids used in plugin xml exist`() {
        val xml = File("src/main/resources/META-INF")
            .listFiles { f -> f.extension == "xml" }!!
            .joinToString("\n") { it.readText() }

        val keys = Regex("""\bkey="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()
        val actions = Regex("""<action\s+id="([^"]+)"""").findAll(xml).map { "action.${it.groupValues[1]}.text" }.toSet()
        val missing = (keys + actions) - properties.stringPropertyNames()

        assertTrue(missing.isEmpty(), "Missing: $missing")
    }
}
