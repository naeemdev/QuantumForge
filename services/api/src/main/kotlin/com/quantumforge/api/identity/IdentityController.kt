package com.quantumforge.api.identity

import com.quantumforge.api.access.AccessService
import com.quantumforge.api.access.Permission
import com.quantumforge.api.common.Page
import com.quantumforge.api.common.PageRequest
import com.quantumforge.api.config.QuantumForgeProperties
import com.quantumforge.api.security.AuthMethod
import com.quantumforge.api.security.CurrentCaller
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class MeResponse(
    val id: UUID,
    val email: String?,
    val displayName: String?,
    val authMethod: AuthMethod,
    val memberships: List<MembershipView>,
)

data class MembershipView(val organizationId: UUID, val organizationName: String, val organizationSlug: String, val role: String, val permissions: List<String>)

data class OrganizationView(val id: UUID, val name: String, val slug: String, val status: String, val role: String?, val createdAt: Instant, val version: Long)

data class CreateOrganizationRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:Size(min = 2, max = 63) val slug: String? = null,
)

data class UpdateOrganizationRequest(@field:NotBlank @field:Size(max = 200) val name: String, val version: Long)

data class CreateInvitationRequest(@field:NotBlank @field:Size(max = 320) val email: String, @field:NotBlank val role: String)

data class InvitationCreatedResponse(val invitation: Invitation, val token: String)

data class AcceptInvitationRequest(@field:NotBlank @field:Size(max = 200) val token: String)

data class ChangeRoleRequest(@field:NotBlank val role: String, val version: Long)

data class CreateApiTokenRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:Min(1) @field:Max(365) val expiresInDays: Int = 30,
)

data class ApiTokenCreatedResponse(val token: ApiToken, val secret: String)

data class AuthConfigResponse(val devLoginEnabled: Boolean, val oidcIssuer: String?)

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Identity")
class IdentityController(
    private val organizations: OrganizationService,
    private val invitations: InvitationService,
    private val apiTokens: ApiTokenService,
    private val access: AccessService,
    private val properties: QuantumForgeProperties,
) {
    @GetMapping("/auth/config")
    fun authConfig() = AuthConfigResponse(properties.auth.devLogin.enabled, properties.auth.oidc.issuerUri)

    @GetMapping("/me")
    fun me(): MeResponse {
        val caller = CurrentCaller.get()
        val orgs = organizations.listForCaller(caller, PageRequest(0, PageRequest.MAX_SIZE)).items
        val memberships = orgs.mapNotNull { summary ->
            access.resolve(summary.organization.id, caller)?.let { ctx ->
                MembershipView(summary.organization.id, summary.organization.name, summary.organization.slug, ctx.roleId, ctx.permissions.map { it.code }.sorted())
            }
        }
        return MeResponse(caller.userId, caller.email, caller.displayName, caller.authMethod, memberships)
    }

    @GetMapping("/organizations")
    fun listOrganizations(@RequestParam(required = false) page: Int?, @RequestParam(required = false) size: Int?): Page<OrganizationView> {
        val result = organizations.listForCaller(CurrentCaller.get(), PageRequest.of(page, size))
        return Page(result.items.map { it.organization.toView(it.roleId) }, result.page, result.size, result.total)
    }

    @PostMapping("/organizations")
    fun createOrganization(@Valid @RequestBody body: CreateOrganizationRequest): ResponseEntity<OrganizationView> {
        val org = organizations.create(CurrentCaller.get(), body.name.trim(), body.slug)
        return ResponseEntity.created(URI.create("/api/v1/organizations/${org.id}")).body(org.toView("org_owner"))
    }

    @GetMapping("/organizations/{id}")
    fun getOrganization(@PathVariable id: UUID): OrganizationView {
        val ctx = access.require(id, Permission.ORG_READ)
        return organizations.get(id).toView(ctx.roleId)
    }

    @PatchMapping("/organizations/{id}")
    fun updateOrganization(@PathVariable id: UUID, @Valid @RequestBody body: UpdateOrganizationRequest): OrganizationView {
        val ctx = access.require(id, Permission.ORG_UPDATE)
        return organizations.rename(ctx, body.name.trim(), body.version).toView(ctx.roleId)
    }

    @GetMapping("/organizations/{id}/workspaces")
    fun workspaces(@PathVariable id: UUID) = organizations.workspaces(access.require(id, Permission.ORG_READ))

    @GetMapping("/organizations/{id}/members")
    fun members(@PathVariable id: UUID, @RequestParam(required = false) page: Int?, @RequestParam(required = false) size: Int?): Page<Member> =
        organizations.members(access.require(id, Permission.ORG_READ), PageRequest.of(page, size))

    @PatchMapping("/organizations/{id}/members/{membershipId}")
    fun changeRole(@PathVariable id: UUID, @PathVariable membershipId: UUID, @Valid @RequestBody body: ChangeRoleRequest): Member =
        organizations.changeRole(access.require(id, Permission.ORG_MEMBERS_MANAGE), membershipId, body.role, body.version)

    @DeleteMapping("/organizations/{id}/members/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeMember(@PathVariable id: UUID, @PathVariable membershipId: UUID) =
        organizations.removeMember(access.require(id, Permission.ORG_MEMBERS_MANAGE), membershipId)

    @GetMapping("/organizations/{id}/invitations")
    fun listInvitations(@PathVariable id: UUID) = invitations.list(access.require(id, Permission.ORG_MEMBERS_MANAGE))

    @PostMapping("/organizations/{id}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    fun invite(@PathVariable id: UUID, @Valid @RequestBody body: CreateInvitationRequest): InvitationCreatedResponse {
        val created = invitations.create(access.require(id, Permission.ORG_MEMBERS_MANAGE), body.email, body.role)
        return InvitationCreatedResponse(created.invitation, created.token)
    }

    @DeleteMapping("/organizations/{id}/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeInvitation(@PathVariable id: UUID, @PathVariable invitationId: UUID) =
        invitations.revoke(access.require(id, Permission.ORG_MEMBERS_MANAGE), invitationId)

    @PostMapping("/invitations/accept")
    fun acceptInvitation(@Valid @RequestBody body: AcceptInvitationRequest): OrganizationView {
        val orgId = invitations.accept(CurrentCaller.get(), body.token.trim())
        val ctx = access.require(orgId, Permission.ORG_READ)
        return organizations.get(orgId).toView(ctx.roleId)
    }

    @GetMapping("/organizations/{id}/api-tokens")
    fun listTokens(@PathVariable id: UUID) = apiTokens.listOwn(access.require(id, Permission.ORG_TOKENS_MANAGE))

    @PostMapping("/organizations/{id}/api-tokens")
    @ResponseStatus(HttpStatus.CREATED)
    fun createToken(@PathVariable id: UUID, @Valid @RequestBody body: CreateApiTokenRequest): ApiTokenCreatedResponse {
        val created = apiTokens.create(access.require(id, Permission.ORG_TOKENS_MANAGE), body.name.trim(), Duration.ofDays(body.expiresInDays.toLong()))
        return ApiTokenCreatedResponse(created.token, created.secret)
    }

    @DeleteMapping("/organizations/{id}/api-tokens/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeToken(@PathVariable id: UUID, @PathVariable tokenId: UUID) =
        apiTokens.revoke(access.require(id, Permission.ORG_TOKENS_MANAGE), tokenId)

    private fun Organization.toView(role: String?) = OrganizationView(id, name, slug, status, role, createdAt, version)
}
