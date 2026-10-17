package com.quantumforge.worker

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses JUnit XML produced by the job. The file is written by untrusted code,
 * so DTDs and external entities are disabled (XXE) and every field is bounded.
 */
object JunitParser {
    private const val MAX_CASES = 5000

    fun parse(file: Path, framework: String): TestRunReport {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
            isNamespaceAware = false
        }
        val document = factory.newDocumentBuilder().parse(file.toFile())
        val nodes = document.getElementsByTagName("testcase")
        val cases = ArrayList<TestCaseReport>()
        var totalMs = 0.0
        for (i in 0 until minOf(nodes.length, MAX_CASES)) {
            val el = nodes.item(i) as Element
            val seconds = el.getAttribute("time").toDoubleOrNull()
            seconds?.let { totalMs += it * 1000 }
            val failure = child(el, "failure")
            val error = child(el, "error")
            val skipped = child(el, "skipped")
            val status = when {
                failure != null -> TestStatus.FAILED
                error != null -> TestStatus.ERRORED
                skipped != null -> TestStatus.SKIPPED
                else -> TestStatus.PASSED
            }
            val problem = failure ?: error
            cases += TestCaseReport(
                suite = suiteName(el),
                name = el.getAttribute("name").ifBlank { "(unnamed)" }.take(1000),
                status = status,
                durationMs = seconds?.let { (it * 1000).toLong() },
                failureMessage = problem?.getAttribute("message")?.takeIf { it.isNotBlank() }?.take(4000),
                failureDetail = problem?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.take(16000),
            )
        }
        return TestRunReport(framework, totalMs.toLong(), cases)
    }

    private fun child(el: Element, tag: String): Element? {
        var node = el.firstChild
        while (node != null) {
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tag) return node as Element
            node = node.nextSibling
        }
        return null
    }

    /** Nearest enclosing <testsuite> name, else a meaningful classname. */
    private fun suiteName(el: Element): String? {
        var parent = el.parentNode
        val names = ArrayList<String>()
        while (parent is Element) {
            if (parent.nodeName == "testsuite") parent.getAttribute("name").takeIf { it.isNotBlank() }?.let { names += it }
            parent = parent.parentNode
        }
        if (names.isNotEmpty()) return names.reversed().joinToString(" › ").take(500)
        return el.getAttribute("classname").takeIf { it.isNotBlank() && it != "test" }?.take(500)
    }
}
