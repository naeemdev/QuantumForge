package com.quantumforge.api.access

import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RoleView(val id: String, val name: String, val description: String, val rank: Int, val permissions: List<String>)

data class PermissionView(val code: String, val description: String)

data class RoleCatalog(val roles: List<RoleView>, val permissions: List<PermissionView>)

/** Built-in roles and what they grant, read from the seeded tables. */
@RestController
@RequestMapping("/api/v1/roles")
@Tag(name = "Identity")
class RoleController(private val jdbc: JdbcClient) {
    @GetMapping
    fun catalog(): RoleCatalog {
        val grants = jdbc.sql("SELECT role_id, permission_code FROM role_permissions ORDER BY permission_code")
            .query { rs, _ -> rs.getString("role_id") to rs.getString("permission_code") }.list()
            .groupBy({ it.first }, { it.second })
        val roles = jdbc.sql("SELECT id, name, description, rank FROM roles WHERE organization_id IS NULL ORDER BY rank DESC")
            .query { rs, _ -> RoleView(rs.getString("id"), rs.getString("name"), rs.getString("description"), rs.getInt("rank"), grants[rs.getString("id")].orEmpty()) }
            .list()
        val permissions = jdbc.sql("SELECT code, description FROM permissions ORDER BY code")
            .query { rs, _ -> PermissionView(rs.getString("code"), rs.getString("description")) }.list()
        return RoleCatalog(roles, permissions)
    }
}
