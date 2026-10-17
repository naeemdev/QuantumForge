"use client";

// Self-hosted Monaco: the editor core plus syntax colouring for common
// languages. No CDN is involved, and no language services or workers beyond
// the base editor worker are loaded, because the viewer is read-only.
import { loader } from "@monaco-editor/react";
import * as monaco from "monaco-editor/esm/vs/editor/edcore.main.js";
import "monaco-editor/esm/vs/basic-languages/typescript/typescript.contribution.js";
import "monaco-editor/esm/vs/basic-languages/javascript/javascript.contribution.js";
import "monaco-editor/esm/vs/basic-languages/kotlin/kotlin.contribution.js";
import "monaco-editor/esm/vs/basic-languages/java/java.contribution.js";
import "monaco-editor/esm/vs/basic-languages/python/python.contribution.js";
import "monaco-editor/esm/vs/basic-languages/go/go.contribution.js";
import "monaco-editor/esm/vs/basic-languages/rust/rust.contribution.js";
import "monaco-editor/esm/vs/basic-languages/markdown/markdown.contribution.js";
import "monaco-editor/esm/vs/basic-languages/yaml/yaml.contribution.js";
import "monaco-editor/esm/vs/basic-languages/shell/shell.contribution.js";
import "monaco-editor/esm/vs/basic-languages/sql/sql.contribution.js";
import "monaco-editor/esm/vs/basic-languages/html/html.contribution.js";
import "monaco-editor/esm/vs/basic-languages/css/css.contribution.js";
import "monaco-editor/esm/vs/basic-languages/xml/xml.contribution.js";
import "monaco-editor/esm/vs/basic-languages/dockerfile/dockerfile.contribution.js";

declare global {
  interface Window {
    MonacoEnvironment?: { getWorker(workerId: string, label: string): Worker };
  }
}

if (typeof window !== "undefined") {
  window.MonacoEnvironment = {
    getWorker: () => new Worker(new URL("monaco-editor/esm/vs/editor/editor.worker.js", import.meta.url), { type: "module" }),
  };
  loader.config({ monaco });

  monaco.editor.defineTheme("qf-light", {
    base: "vs",
    inherit: true,
    rules: [],
    colors: { "editor.background": "#ffffff", "editorLineNumber.foreground": "#9aa0aa", "editor.lineHighlightBackground": "#f3f5f8" },
  });
  monaco.editor.defineTheme("qf-dark", {
    base: "vs-dark",
    inherit: true,
    rules: [],
    colors: { "editor.background": "#14171b", "editorLineNumber.foreground": "#5c626d", "editor.lineHighlightBackground": "#1b1f24" },
  });
}

export { monaco };

/** Maps the platform's language labels to Monaco language ids. */
export function monacoLanguage(language: string | null | undefined): string {
  switch (language) {
    case "TypeScript": return "typescript";
    case "JavaScript": return "javascript";
    case "Kotlin": return "kotlin";
    case "Java": return "java";
    case "Python": return "python";
    case "Go": return "go";
    case "Rust": return "rust";
    case "Markdown": return "markdown";
    case "YAML": return "yaml";
    case "Shell": return "shell";
    case "SQL": return "sql";
    case "HTML": return "html";
    case "CSS": case "SCSS": return "css";
    case "XML": return "xml";
    case "Dockerfile": return "dockerfile";
    case "JSON": return "json";
    default: return "plaintext";
  }
}
