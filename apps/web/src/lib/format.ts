const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });
const relative = new Intl.RelativeTimeFormat(undefined, { numeric: "auto" });

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return "—";
  return dateTime.format(new Date(value));
}

export function formatRelative(value: string | null | undefined, now = Date.now()): string {
  if (!value) return "—";
  const seconds = Math.round((Date.parse(value) - now) / 1000);
  const abs = Math.abs(seconds);
  if (abs < 45) return relative.format(seconds, "second");
  if (abs < 2700) return relative.format(Math.round(seconds / 60), "minute");
  if (abs < 79200) return relative.format(Math.round(seconds / 3600), "hour");
  return relative.format(Math.round(seconds / 86400), "day");
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return "—";
  if (bytes < 1024) return `${bytes} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit]}`;
}

export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return "—";
  if (ms < 1000) return `${ms} ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(s < 10 ? 2 : 1)} s`;
  const m = Math.floor(s / 60);
  return `${m}m ${Math.round(s % 60)}s`;
}

export function formatNumber(n: number | null | undefined): string {
  return n == null ? "—" : n.toLocaleString();
}

export function shortId(id: string): string {
  return id.slice(-8);
}

export function shortDigest(digest: string): string {
  return digest.slice(0, 12);
}
