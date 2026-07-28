package com.bromano.mobile.perf.faults

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidFaultReportTest {
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
