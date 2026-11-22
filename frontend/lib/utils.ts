import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function fmtMs(v: number | null | undefined): string {
  if (v == null) return "—"
  return `${v.toFixed(2)} ms`
}

export function fmtPct(v: number | null | undefined): string {
  if (v == null) return "—"
  return `${(v * 100).toFixed(1)}%`
}

export function fmtMetric(v: number | null | undefined): string {
  if (v == null) return "—"
  return v.toFixed(4)
}

export function fmtNum(v: number | null | undefined): string {
  if (v == null) return "—"
  return v.toLocaleString()
}
