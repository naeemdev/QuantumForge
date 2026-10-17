package com.quantumforge.api.access

/** Mirrors the `permissions` table seeded in V1. Codes are the stable contract. */
enum class Permission(val code: String) {
    ORG_READ("org.read"),
    ORG_UPDATE("org.update"),
    ORG_MEMBERS_MANAGE("org.members.manage"),
    ORG_AUDIT_READ("org.audit.read"),
    ORG_TOKENS_MANAGE("org.tokens.manage"),
    PROJECT_READ("project.read"),
    PROJECT_CREATE("project.create"),
    PROJECT_UPDATE("project.update"),
    SOURCE_IMPORT("source.import"),
    JOB_READ("job.read"),
    JOB_SUBMIT("job.submit"),
    JOB_CANCEL("job.cancel"),
    FINDING_TRIAGE("finding.triage"),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun fromCode(code: String): Permission? = byCode[code]
    }
}

object Roles {
    const val OWNER = "org_owner"
    const val ADMIN = "org_admin"
    const val MEMBER = "org_member"
    const val VIEWER = "org_viewer"

    val all = listOf(OWNER, ADMIN, MEMBER, VIEWER)
}
