package com.shifa.admin.application

import com.shifa.admin.domain.SystemConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AppGateConfigTest {

    private fun block(vararg rows: Pair<String, String>): Map<*, *> {
        val configs = mock(SystemConfigs::class.java)
        `when`(configs.findAll()).thenReturn(
            rows.map { (k, v) -> SystemConfig(key = k, value = v) } + SystemConfig(key = "password_min_length", value = "8"),
        )
        return AppGateConfig(configs).publicConfig()["doctorApp"] as Map<*, *>
    }

    @Test
    fun `empty values mean no gate`() {
        val app = block("doctor_app.min_version.android" to "", "doctor_app.maintenance.active" to "false")

        assertEquals(mapOf("android" to null, "ios" to null), app["minVersion"])
        val maintenance = app["maintenance"] as Map<*, *>
        assertEquals(false, maintenance["active"])
        assertEquals(mapOf("en" to null, "uz" to null, "ru" to null), maintenance["message"])
        assertNull(maintenance["until"])
    }

    @Test
    fun `set values come through per platform and language`() {
        val app = block(
            "doctor_app.min_version.android" to "1.0.1",
            "doctor_app.latest_version.ios" to " 1.1.3 ",
            "doctor_app.store_url.android" to "https://play.google.com/store/apps/details?id=com.shifa.doc",
            "doctor_app.maintenance.active" to "TRUE",
            "doctor_app.maintenance.message.ru" to "Технические работы",
            "doctor_app.maintenance.until" to "2026-11-08T05:00:00Z",
        )

        assertEquals("1.0.1", (app["minVersion"] as Map<*, *>)["android"])
        assertEquals("1.1.3", (app["latestVersion"] as Map<*, *>)["ios"])
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.shifa.doc",
            (app["storeUrl"] as Map<*, *>)["android"],
        )
        val maintenance = app["maintenance"] as Map<*, *>
        assertEquals(true, maintenance["active"])
        assertEquals("Технические работы", (maintenance["message"] as Map<*, *>)["ru"])
        assertEquals("2026-11-08T05:00:00Z", maintenance["until"])
    }
}
