package io.github.jacobjoergensen.laravelfmt

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class BinaryCacheTest {

    @TempDir
    lateinit var temp: Path

    private fun source(content: String): Path {
        val dir = Files.createDirectories(temp.resolve("vendor-" + content.hashCode()))
        return Files.writeString(dir.resolve("laravel-fmt-Linux-x86_64"), content)
    }

    @Test
    fun `copies the binary into the cache under its own name`() {
        val src = source("v1")
        val cache = BinaryCache(temp.resolve("cache"))

        val copy = cache.materialize(src)

        assertTrue(copy.startsWith(temp.resolve("cache")))
        assertEquals("laravel-fmt-Linux-x86_64", copy.fileName.toString())
        assertArrayEquals(Files.readAllBytes(src), Files.readAllBytes(copy))
    }

    @Test
    fun `same content reuses the same copy`() {
        val cache = BinaryCache(temp.resolve("cache"))

        val first = cache.materialize(source("v1"))
        val second = cache.materialize(source("v1"))

        assertEquals(first, second)
    }

    @Test
    fun `a tampered copy of the same size is replaced`() {
        val src = source("v1")
        val cache = BinaryCache(temp.resolve("cache"))
        val copy = cache.materialize(src)
        Files.writeString(copy, "x1")

        cache.materialize(src)

        assertArrayEquals(Files.readAllBytes(src), Files.readAllBytes(copy))
    }

    @Test
    fun `another project's freshly prepared copy is not deleted`() {
        val cache = BinaryCache(temp.resolve("cache"))

        val projectA = cache.materialize(source("v1"))
        cache.materialize(source("v2"))

        assertTrue(Files.exists(projectA))
    }

    @Test
    fun `a version unused for over a week is pruned`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")

        val old = BinaryCache(temp.resolve("cache"), Clock.fixed(now, ZoneOffset.UTC)).materialize(source("v1"))
        val new = BinaryCache(temp.resolve("cache"), Clock.fixed(now.plus(Duration.ofDays(8)), ZoneOffset.UTC)).materialize(source("v2"))

        assertNotEquals(old.parent, new.parent)
        assertTrue(Files.exists(new))
        assertFalse(Files.exists(old))
    }

    @Test
    fun `leaves no partial files behind`() {
        val cache = BinaryCache(temp.resolve("cache"))
        val copy = cache.materialize(source("v1"))

        Files.list(copy.parent).use { files ->
            assertEquals(listOf(copy), files.toList())
        }
    }
}
