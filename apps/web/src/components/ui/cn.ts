import { twMerge } from "tailwind-merge";

/** Joins class names; later Tailwind utilities override conflicting earlier ones (e.g. w-64 over w-full). */
export function cn(...classes: (string | false | null | undefined)[]): string {
  return twMerge(classes.filter(Boolean).join(" "));
}
