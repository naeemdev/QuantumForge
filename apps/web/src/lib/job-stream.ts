"use client";

import { useEffect, useState } from "react";
import type { JobEvent } from "@quantumforge/contracts";
import { API_URL } from "./config";
import { tokenStore } from "./token-store";

/**
 * Follows a job's Server-Sent Events stream. EventSource cannot send an
 * Authorization header, so the stream is read with fetch and parsed here.
 * Reconnects with Last-Event-ID until the server sends `end`.
 */
export function useJobEvents(jobId: string): { events: JobEvent[]; live: boolean; error: string | null } {
  // Events are tagged with their job so navigating to another job never shows stale ones.
  const [stream, setStream] = useState<{ jobId: string; events: JobEvent[] }>({ jobId, events: [] });
  const events = stream.jobId === jobId ? stream.events : [];
  const [live, setLive] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    let lastId = 0;
    let finished = false;

    async function connect(attempt: number): Promise<void> {
      try {
        const response = await fetch(`${API_URL}/api/v1/jobs/${jobId}/stream`, {
          headers: { Authorization: `Bearer ${tokenStore.get() ?? ""}`, Accept: "text/event-stream", ...(lastId ? { "Last-Event-ID": String(lastId) } : {}) },
          signal: controller.signal,
        });
        if (!response.ok || !response.body) throw new Error(`stream failed (${response.status})`);
        setLive(true);
        setError(null);
        const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
        let buffer = "";
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffer += value;
          let boundary: number;
          while ((boundary = buffer.indexOf("\n\n")) >= 0) {
            const frame = buffer.slice(0, boundary);
            buffer = buffer.slice(boundary + 2);
            const fields = parseFrame(frame);
            if (fields.event === "end") {
              finished = true;
              continue;
            }
            if (!fields.data) continue;
            const event = JSON.parse(fields.data) as JobEvent;
            lastId = event.id;
            setStream((prev) => {
              const current = prev.jobId === jobId ? prev.events : [];
              return current.some((e) => e.id === event.id) ? prev : { jobId, events: [...current, event] };
            });
          }
        }
      } catch (e) {
        if (controller.signal.aborted) return;
        setError(e instanceof Error ? e.message : "stream error");
      } finally {
        setLive(false);
      }
      if (!finished && !controller.signal.aborted && attempt < 20) {
        await new Promise((r) => setTimeout(r, Math.min(1000 * 2 ** attempt, 15_000)));
        return connect(attempt + 1);
      }
    }

    void connect(0);
    return () => controller.abort();
  }, [jobId]);

  return { events, live, error };
}

function parseFrame(frame: string): { event?: string; data?: string } {
  const out: { event?: string; data?: string } = {};
  for (const line of frame.split("\n")) {
    if (line.startsWith("event:")) out.event = line.slice(6).trim();
    else if (line.startsWith("data:")) out.data = (out.data ? out.data + "\n" : "") + line.slice(5).trimStart();
  }
  return out;
}
