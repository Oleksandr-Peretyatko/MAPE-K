"use client"

interface Props {
  current: number
  previous: number
}

const NEUTRAL_THRESHOLD = 0.02

export function QualityScoreDeltaBadge({ current, previous }: Props) {
  const delta = current - previous
  if (Math.abs(delta) < NEUTRAL_THRESHOLD) {
    return <span className="text-xs text-muted-foreground">— 0%</span>
  }
  const pct = Math.round(Math.abs(delta) * 100)
  if (delta > 0) {
    return <span className="text-xs font-medium text-green-600 dark:text-green-400">▲ +{pct}%</span>
  }
  return <span className="text-xs font-medium text-destructive">▼ -{pct}%</span>
}
