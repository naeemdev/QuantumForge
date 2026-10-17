package com.quantumforge.api.support

import com.quantumforge.api.config.InternalPortHolder
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.ServerSocket
import java.nio.file.Files

/**
 * Boots the real application against PostgreSQL (QF_TEST_DB_URL, default
 * jdbc:postgresql://localhost:5432/quantumforge_test). The schema is rebuilt
 * once per JVM. Tests isolate themselves by creating their own users and
 * organizations rather than truncating shared tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class IntegrationTest {
    @LocalServerPort
    protected var publicPort: Int = 0

    @Autowired
    protected lateinit var internalPort: InternalPortHolder

    @Autowired
    protected lateinit var jdbc: JdbcClient

    protected val api by lazy { ApiClient("http://localhost:$publicPort") }

    protected val internalUrl get() = "http://localhost:${internalPort.port}"

    /** Jobs are leased from one global queue; park other tests' leftovers so they cannot be picked up. */
    @BeforeEach
    fun parkQueuedJobs() {
        jdbc.sql("UPDATE analysis_jobs SET state = 'CANCELLED', finished_at = now(), failure_reason = 'parked by test harness' WHERE state = 'QUEUED'").update()
    }

    companion object {
        const val WORKER_TOKEN = "integration-test-worker-token"
        const val CONTAINER_WORKER_TOKEN = "integration-test-container-worker"

        private val dbUrl = System.getenv("QF_TEST_DB_URL") ?: "jdbc:postgresql://localhost:5432/quantumforge_test"
        private val dbUser = System.getenv("QF_TEST_DB_USER") ?: "quantumforge"
        private val dbPassword = System.getenv("QF_TEST_DB_PASSWORD") ?: "quantumforge"
        private val internal = ServerSocket(0).use { it.localPort }
        private val blobRoot = Files.createTempDirectory("qf-test-blobs")

        init {
            Flyway.configure().dataSource(dbUrl, dbUser, dbPassword).cleanDisabled(false).load().clean()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { dbUrl }
            registry.add("spring.datasource.username") { dbUser }
            registry.add("spring.datasource.password") { dbPassword }
            registry.add("quantumforge.internal-api.port") { internal }
            registry.add("quantumforge.storage.filesystem-root") { blobRoot.toString() }
            registry.add("quantumforge.auth.dev-login.enabled") { true }
            registry.add("quantumforge.execution.allow-dev-isolation") { true }
            registry.add("quantumforge.execution.lease-duration") { "5s" }
            registry.add("quantumforge.execution.reaper-interval") { "1h" }
            registry.add("quantumforge.rate-limit.requests-per-minute") { 0 }
            registry.add("quantumforge.execution.bootstrap-workers[0].name") { "test-worker" }
            registry.add("quantumforge.execution.bootstrap-workers[0].isolation-mode") { "dev-process" }
            registry.add("quantumforge.execution.bootstrap-workers[0].token") { WORKER_TOKEN }
            registry.add("quantumforge.execution.bootstrap-workers[1].name") { "test-container-worker" }
            registry.add("quantumforge.execution.bootstrap-workers[1].isolation-mode") { "container" }
            registry.add("quantumforge.execution.bootstrap-workers[1].token") { CONTAINER_WORKER_TOKEN }
        }
    }
}
