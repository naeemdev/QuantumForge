"use client";

import { useEffect, useRef, useState } from "react";
import Editor, { type OnMount } from "@monaco-editor/react";
import { monaco, monacoLanguage } from "@/lib/monaco";

export interface CodeMarker {
  startLine: number;
  startColumn: number;
  endLine: number;
  endColumn: number;
  message: string;
  severity: string;
}

function useDarkMode(): boolean {
  const [dark, setDark] = useState(() => typeof document !== "undefined" && document.documentElement.classList.contains("dark"));
  useEffect(() => {
    const observer = new MutationObserver(() => setDark(document.documentElement.classList.contains("dark")));
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
    return () => observer.disconnect();
  }, []);
  return dark;
}

const severityOf = (s: string) =>
  s === "critical" || s === "high" ? monaco.MarkerSeverity.Error : s === "medium" ? monaco.MarkerSeverity.Warning : monaco.MarkerSeverity.Info;

/** Read-only Monaco viewer with finding markers and an optional line to reveal. */
export function CodeViewer({ value, language, path, markers = [], revealLine, height = "70vh" }: {
  value: string;
  language: string | null | undefined;
  path: string;
  markers?: CodeMarker[];
  revealLine?: number;
  height?: string;
}) {
  const dark = useDarkMode();
  const editorRef = useRef<Parameters<OnMount>[0] | null>(null);

  const apply = () => {
    const editor = editorRef.current;
    const model = editor?.getModel();
    if (!editor || !model) return;
    monaco.editor.setModelMarkers(model, "quantumforge", markers.map((m) => ({
      startLineNumber: m.startLine,
      startColumn: m.startColumn,
      endLineNumber: m.endLine,
      endColumn: m.endColumn,
      message: m.message,
      severity: severityOf(m.severity),
      source: "QuantumForge",
    })));
    if (revealLine) {
      editor.revealLineInCenter(revealLine);
      editor.setSelection(new monaco.Selection(revealLine, 1, revealLine, model.getLineMaxColumn(revealLine)));
    }
  };

  useEffect(apply);

  return (
    <Editor
      height={height}
      path={path}
      value={value}
      language={monacoLanguage(language)}
      theme={dark ? "qf-dark" : "qf-light"}
      onMount={(editor) => {
        editorRef.current = editor;
        apply();
      }}
      options={{
        readOnly: true,
        domReadOnly: true,
        minimap: { enabled: false },
        scrollBeyondLastLine: false,
        fontFamily: "'JetBrains Mono Variable', ui-monospace, monospace",
        fontSize: 12.5,
        lineHeight: 20,
        renderLineHighlight: "line",
        glyphMargin: true,
        automaticLayout: true,
        contextmenu: false,
        stickyScroll: { enabled: false },
      }}
      loading={<div className="p-4 text-[13px] text-fg-3">Loading editor…</div>}
    />
  );
}
