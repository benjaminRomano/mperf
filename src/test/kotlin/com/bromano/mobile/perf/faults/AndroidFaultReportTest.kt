package com.bromano.mobile.perf.faults

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidFaultReportTest {
    @Test fun `comparison requires matching known compilation preparation and actual ART filters`() {
        fun state(
            mode: String,
            filter: String,
        ): Map<String, Any> =
            mapOf(
                "compilation_mode" to mode,
                "speed_profile_verified_before" to (filter == "speed-profile"),
                "speed_profile_verified_after" to (filter == "speed-profile"),
                "compilation_before" to mapOf("exit_code" to 0, "statuses" to listOf(filter)),
                "compilation_after" to mapOf("exit_code" to 0, "statuses" to listOf(filter)),
            )
        val profiled = state("speed-profile", "speed-profile")
        assertEquals(emptyList(), compilationDifferences(profiled, profiled))
        for (filter in listOf("speed-profile", "speed", "verify")) {
            assertTrue(compilationDifferences(profiled, state("as-is", filter)).isNotEmpty())
        }
        assertTrue(compilationDifferences(profiled, state("speed-profile", "verify")).isNotEmpty())
        assertTrue(compilationDifferences(emptyMap<String, Any>(), emptyMap<String, Any>()).isNotEmpty())
    }

    @Test
    fun `app source labels are stable across randomized install roots`() {
        assertEquals(
            "base.apk",
            stableAndroidSourceLabel(
                "/data/app/~~first/com.example.app-random/base.apk",
                "com.example.app",
            ),
        )
        assertEquals(
            "oat/arm64/base.vdex",
            stableAndroidSourceLabel(
                "/data/app/~~second/com.example.app-other/oat/arm64/base.vdex",
                "com.example.app",
            ),
        )
        assertEquals(
            "files/startup.bin",
            stableAndroidSourceLabel(
                "/data/user/0/com.example.app/files/startup.bin",
                "com.example.app",
            ),
        )
    }

    @Test
    fun `non-app source labels preserve their complete paths`() {
        assertEquals(
            "/system/lib64/libx.so",
            stableAndroidSourceLabel("/system/lib64/libx.so", "com.example.app"),
        )
        assertEquals(
            "/vendor/lib64/libx.so",
            stableAndroidSourceLabel("/vendor/lib64/libx.so", "com.example.app"),
        )
    }
}
