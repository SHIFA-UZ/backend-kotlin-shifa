package com.shifa.notifications

import com.shifa.notifications.domain.Notification
import java.io.File
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The published push contract (docs/api/push-notifications.md) lists every notification type. */
class PushNotificationsDocTest {

    @Test
    fun `every notification type is documented`() {
        val doc = File("docs/api/push-notifications.md").readText()
        val missing = Notification.Type.entries.map { it.name }.filterNot { doc.contains("`$it`") }
        assertTrue(missing.isEmpty(), "Add to docs/api/push-notifications.md: $missing")
    }
}
