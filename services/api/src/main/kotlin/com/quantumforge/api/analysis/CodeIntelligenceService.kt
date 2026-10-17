package com.quantumforge.api.analysis

import com.quantumforge.api.access.OrgAccess
import com.quantumforge.api.common.Json
import com.quantumforge.api.common.NotFoundException
import com.quantumforge.api.common.Page
import com.quantumforge.api.common.PageRequest
import com.quantumforge.api.common.instant
import com.quantumforge.api.common.intOrNull
import com.quantumforge.api.common.uuid
import com.quantumforge.api.projects.ProjectService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class CodeIndexView(
    val id: UUID,
    val snapshotId: UUID,
    val jobId: UUID,
    val adapterId: String,
    val adapterVersion: String,
    val toolchain: String,
    val capabilities: Map<String, Any?>,
    val limitations: List<String>,
    val stats: Map<String, Any?>,
    val fileCount: Int,
    val symbolCount: Int,
    val importCount: Int,
    val callCount: Int,
    val createdAt: Instant,
)

data class CodeSymbolView(
    val key: String,
    val kind: String,
    val name: String,
    val container: String?,
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val exported: Boolean,
    val complexity: Int?,
    val signature: String?,
)

data class DependencyNode(val path: String, val language: String, val lineCount: Int, val symbolCount: Int, val openFindings: Int, val fanIn: Int, val fanOut: Int)

data class DependencyEdge(val from: String, val to: String, val count: Int)

data class ExternalDependency(val packageName: String, val importedBy: Int)

data class DependencyGraph(val indexId: UUID, val nodes: List<DependencyNode>, val edges: List<DependencyEdge>, val external: List<ExternalDependency>, val unresolved: Int)

data class CallEdgeView(val from: String, val to: String, val path: String, val line: Int)

data class FileAnnotations(val path: String, val indexId: UUID?, val symbols: List<CodeSymbolView>, val findings: List<FindingOccurrenceView>, val diagnostics: List<Map<String, Any?>>)

/** Read side of the code index. Every query is scoped by tenant and authorised project. */
@Service
class CodeIntelligenceService(private val jdbc: JdbcClient, private val projects: ProjectService) {
    /** Most recent index for a snapshot, or for the project's newest analysed snapshot. */
    fun latestIndex(ctx: OrgAccess, projectId: UUID, snapshotId: UUID?): CodeIndexView? {
        projects.get(ctx, projectId)
        val filter = if (snapshotId != null) "AND i.snapshot_id = :snapshot" else ""
        return jdbc.sql(
            """
            SELECT i.*,
              (SELECT count(*) FROM code_files f WHERE f.index_id = i.id) AS file_count,
              (SELECT count(*) FROM code_symbols s WHERE s.index_id = i.id) AS symbol_count,
              (SELECT count(*) FROM code_imports m WHERE m.index_id = i.id) AS import_count,
              (SELECT count(*) FROM code_calls c WHERE c.index_id = i.id) AS call_count
            FROM code_indexes i JOIN source_snapshots s ON s.id = i.snapshot_id
            WHERE i.project_id = :project AND i.tenant_id = :tenant $filter
            ORDER BY s.created_at DESC, i.created_at DESC LIMIT 1
            """,
        ).param("project", projectId).param("tenant", ctx.tenantId).apply { if (snapshotId != null) param("snapshot", snapshotId) }
            .query(::mapIndex).optional().orElse(null)
    }

    fun indexForJob(ctx: OrgAccess, jobId: UUID): CodeIndexView? =
        jdbc.sql(
            """
            SELECT i.*,
              (SELECT count(*) FROM code_files f WHERE f.index_id = i.id) AS file_count,
              (SELECT count(*) FROM code_symbols s WHERE s.index_id = i.id) AS symbol_count,
              (SELECT count(*) FROM code_imports m WHERE m.index_id = i.id) AS import_count,
              (SELECT count(*) FROM code_calls c WHERE c.index_id = i.id) AS call_count
            FROM code_indexes i JOIN projects p ON p.id = i.project_id AND p.tenant_id = i.tenant_id
            WHERE i.job_id = :job AND i.tenant_id = :tenant AND p.organization_id = :org
            """,
        ).param("job", jobId).param("tenant", ctx.tenantId).param("org", ctx.organizationId).query(::mapIndex).optional().orElse(null)

    private fun requireIndex(ctx: OrgAccess, projectId: UUID, snapshotId: UUID?): CodeIndexView =
        latestIndex(ctx, projectId, snapshotId) ?: throw NotFoundException("code index", snapshotId ?: projectId)

    fun symbols(ctx: OrgAccess, projectId: UUID, snapshotId: UUID?, query: String?, kind: String?, path: String?, page: PageRequest): Page<CodeSymbolView> {
        val index = requireIndex(ctx, projectId, snapshotId)
        val like = query?.trim()?.takeIf { it.isNotEmpty() }?.let { "%" + it.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }
        val where = buildString {
            append("index_id = :index AND tenant_id = :tenant")
            if (like != null) append(" AND (lower(name) LIKE :q OR lower(symbol_key) LIKE :q)")
            if (kind != null) append(" AND kind = :kind")
            if (path != null) append(" AND path = :path")
        }
        fun bind(s: JdbcClient.StatementSpec) = s.param("index", index.id).param("tenant", ctx.tenantId).apply {
            if (like != null) param("q", like)
            if (kind != null) param("kind", kind)
            if (path != null) param("path", path)
        }
        val items = bind(jdbc.sql("SELECT * FROM code_symbols WHERE $where ORDER BY path, start_line LIMIT :limit OFFSET :offset"))
            .param("limit", page.size).param("offset", page.offset).query(::mapSymbol).list()
        val total = bind(jdbc.sql("SELECT count(*) FROM code_symbols WHERE $where")).query(Long::class.java).single()
        return Page.of(items, page, total)
    }

    fun dependencies(ctx: OrgAccess, projectId: UUID, snapshotId: UUID?): DependencyGraph {
        val index = requireIndex(ctx, projectId, snapshotId)
        val edges = jdbc.sql(
            "SELECT from_path, to_path, count(*) AS n FROM code_imports WHERE index_id = :index AND tenant_id = :tenant AND to_path IS NOT NULL GROUP BY from_path, to_path ORDER BY from_path, to_path",
        ).param("index", index.id).param("tenant", ctx.tenantId)
            .query { rs, _ -> DependencyEdge(rs.getString("from_path"), rs.getString("to_path"), rs.getInt("n")) }.list()
        val fanOut = edges.groupingBy { it.from }.eachCount()
        val fanIn = edges.groupingBy { it.to }.eachCount()
        val nodes = jdbc.sql(
            """
            SELECT f.path, f.language, f.line_count,
              (SELECT count(*) FROM code_symbols s WHERE s.index_id = f.index_id AND s.path = f.path) AS symbols,
              (SELECT count(*) FROM findings x WHERE x.project_id = :project AND x.tenant_id = :tenant AND x.path = f.path
                 AND x.status IN ('OPEN', 'CONFIRMED', 'REOPENED')) AS findings
            FROM code_files f WHERE f.index_id = :index AND f.tenant_id = :tenant ORDER BY f.path
            """,
        ).param("index", index.id).param("tenant", ctx.tenantId).param("project", projectId)
            .query { rs, _ ->
                val path = rs.getString("path")
                DependencyNode(path, rs.getString("language"), rs.getInt("line_count"), rs.getInt("symbols"), rs.getInt("findings"), fanIn[path] ?: 0, fanOut[path] ?: 0)
            }.list()
        val external = jdbc.sql(
            "SELECT package_name, count(DISTINCT from_path) AS n FROM code_imports WHERE index_id = :index AND tenant_id = :tenant AND external GROUP BY package_name ORDER BY n DESC, package_name",
        ).param("index", index.id).param("tenant", ctx.tenantId)
            .query { rs, _ -> ExternalDependency(rs.getString("package_name"), rs.getInt("n")) }.list()
        val unresolved = jdbc.sql("SELECT count(*) FROM code_imports WHERE index_id = :index AND tenant_id = :tenant AND NOT external AND to_path IS NULL")
            .param("index", index.id).param("tenant", ctx.tenantId).query(Int::class.java).single()
        return DependencyGraph(index.id, nodes, edges, external, unresolved)
    }

    fun calls(ctx: OrgAccess, projectId: UUID, snapshotId: UUID?, symbolKey: String?): List<CallEdgeView> {
        val index = requireIndex(ctx, projectId, snapshotId)
        val filter = if (symbolKey != null) "AND (from_key = :key OR to_key = :key)" else ""
        return jdbc.sql("SELECT * FROM code_calls WHERE index_id = :index AND tenant_id = :tenant $filter ORDER BY from_key, to_key LIMIT 5000")
            .param("index", index.id).param("tenant", ctx.tenantId).apply { if (symbolKey != null) param("key", symbolKey) }
            .query { rs, _ -> CallEdgeView(rs.getString("from_key"), rs.getString("to_key"), rs.getString("path"), rs.getInt("line")) }.list()
    }

    /** Symbols, findings and diagnostics of one file, for the code viewer. */
    fun annotations(ctx: OrgAccess, projectId: UUID, snapshotId: UUID, path: String, findings: FindingService): FileAnnotations {
        val index = latestIndex(ctx, projectId, snapshotId) ?: return FileAnnotations(path, null, emptyList(), emptyList(), emptyList())
        val symbols = jdbc.sql("SELECT * FROM code_symbols WHERE index_id = :index AND tenant_id = :tenant AND path = :path ORDER BY start_line")
            .param("index", index.id).param("tenant", ctx.tenantId).param("path", path).query(::mapSymbol).list()
        val diagnostics = jdbc.sql("SELECT diagnostics FROM code_files WHERE index_id = :index AND tenant_id = :tenant AND path = :path")
            .param("index", index.id).param("tenant", ctx.tenantId).param("path", path).query(String::class.java).optional()
            .map { Json.mapper.readValue(it, List::class.java).filterIsInstance<Map<String, Any?>>() }.orElse(emptyList())
        return FileAnnotations(path, index.id, symbols, findings.occurrencesForFile(ctx, index.jobId, path), diagnostics)
    }

    private fun mapIndex(rs: ResultSet, @Suppress("UNUSED_PARAMETER") i: Int) = CodeIndexView(
        id = rs.uuid("id"),
        snapshotId = rs.uuid("snapshot_id"),
        jobId = rs.uuid("job_id"),
        adapterId = rs.getString("adapter_id"),
        adapterVersion = rs.getString("adapter_version"),
        toolchain = rs.getString("toolchain"),
        capabilities = Json.readMap(rs.getString("capabilities")),
        limitations = Json.mapper.readValue(rs.getString("limitations"), List::class.java).map { it.toString() },
        stats = Json.readMap(rs.getString("stats")),
        fileCount = rs.getInt("file_count"),
        symbolCount = rs.getInt("symbol_count"),
        importCount = rs.getInt("import_count"),
        callCount = rs.getInt("call_count"),
        createdAt = rs.instant("created_at"),
    )

    private fun mapSymbol(rs: ResultSet, @Suppress("UNUSED_PARAMETER") i: Int) = CodeSymbolView(
        key = rs.getString("symbol_key"),
        kind = rs.getString("kind"),
        name = rs.getString("name"),
        container = rs.getString("container"),
        path = rs.getString("path"),
        startLine = rs.getInt("start_line"),
        endLine = rs.getInt("end_line"),
        exported = rs.getBoolean("exported"),
        complexity = rs.intOrNull("complexity"),
        signature = rs.getString("signature"),
    )
}
