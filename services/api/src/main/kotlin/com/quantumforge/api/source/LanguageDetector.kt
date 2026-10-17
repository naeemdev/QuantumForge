package com.quantumforge.api.source

import org.springframework.stereotype.Component

/**
 * Extension- and filename-based language classification. This is deliberately
 * shallow: it labels files for statistics and adapter selection; it does not
 * claim the file parses as that language.
 */
@Component
class LanguageDetector {
    private val byExtension = mapOf(
        "ts" to "TypeScript", "tsx" to "TypeScript", "mts" to "TypeScript", "cts" to "TypeScript",
        "js" to "JavaScript", "jsx" to "JavaScript", "mjs" to "JavaScript", "cjs" to "JavaScript",
        "kt" to "Kotlin", "kts" to "Kotlin",
        "java" to "Java",
        "ml" to "OCaml", "mli" to "OCaml",
        "f" to "Fortran", "for" to "Fortran", "f77" to "Fortran", "f90" to "Fortran", "f95" to "Fortran", "f03" to "Fortran", "f08" to "Fortran",
        "cob" to "COBOL", "cbl" to "COBOL", "cpy" to "COBOL",
        "py" to "Python", "go" to "Go", "rs" to "Rust", "rb" to "Ruby", "php" to "PHP", "cs" to "C#", "swift" to "Swift",
        "c" to "C", "h" to "C", "cc" to "C++", "cpp" to "C++", "cxx" to "C++", "hpp" to "C++",
        "scala" to "Scala", "sql" to "SQL", "sh" to "Shell", "bash" to "Shell",
        "json" to "JSON", "yaml" to "YAML", "yml" to "YAML", "toml" to "TOML", "xml" to "XML",
        "md" to "Markdown", "html" to "HTML", "css" to "CSS", "scss" to "SCSS",
    )

    private val byFilename = mapOf(
        "dockerfile" to "Dockerfile",
        "makefile" to "Makefile",
        "dune" to "OCaml",
        "dune-project" to "OCaml",
    )

    fun detect(path: String): String? {
        val filename = path.substringAfterLast('/')
        byFilename[filename.lowercase()]?.let { return it }
        val ext = filename.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == filename.lowercase()) return null
        return byExtension[ext]
    }

    /** Test files by common conventions; used for source-vs-test line statistics. */
    fun isTestPath(path: String): Boolean {
        val lower = path.lowercase()
        val name = lower.substringAfterLast('/')
        return lower.contains("/test/") || lower.contains("/tests/") || lower.startsWith("test/") || lower.startsWith("tests/") ||
            lower.contains("/__tests__/") || name.contains(".test.") || name.contains(".spec.") || name.endsWith("test.kt") || name.endsWith("tests.kt")
    }
}
