package com.shifa

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * The guards against one stuck request holding the pool (production outage 2026-11-11): every pooled connection
 * cancels a statement after 30 s, and Hikari reports a connection held over 20 s. Against PostgreSQL.
 */
@SpringBootTest(properties = ["spring.jpa.hibernate.ddl-auto=validate", "logging.file.name=build/shifa-backend-test.log"])
class DatabaseGuardsIntegrationTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var dataSource: DataSource

    companion object {
        @JvmStatic
        @BeforeAll
        fun requirePostgres() = ApplicationContextTest.requirePostgres()
    }

    @Test
    fun `pooled connections time out long statements and the pool reports held connections`() {
        assertThat(jdbc.queryForObject("SHOW statement_timeout", String::class.java)).isEqualTo("30s")
        assertThat(dataSource.unwrap(HikariDataSource::class.java).leakDetectionThreshold).isEqualTo(20_000)
    }
}
