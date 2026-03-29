package com.bromano.mobile.perf.utils

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VerifiedDownloadTest {
    @Test
    fun `downloadVerified writes content when checksum matches`() {
        val source = createTempFile("download-source", ".bin")
        val target = createTempFile("download-target", ".bin")
        val content = "verified".encodeToByteArray()
        Files.write(source, content)

        downloadVerified(source.toUri().toString(), target, sha256(content))

        assertEquals("verified", Files.readString(target))
    }

    @Test
    fun `downloadVerified throws when checksum does not match`() {
        val source = createTempFile("download-source", ".bin")
        val target = createTempFile("download-target", ".bin")
        Files.writeString(source, "tampered")

        assertFailsWith<IllegalArgumentException> {
            downloadVerified(source.toUri().toString(), target, "deadbeef")
        }
    }
}
