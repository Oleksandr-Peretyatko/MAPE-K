"use client"

import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import type { FormulaExplanation } from "@/lib/api"

interface Props {
  formula: FormulaExplanation
}

export function QualityFormulaTooltip({ formula }: Props) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          type="button"
          className="inline-flex items-center justify-center w-4 h-4 rounded-full text-xs text-muted-foreground border border-muted-foreground/30 hover:text-foreground hover:border-foreground/50 transition-colors ml-1"
          aria-label="How is this calculated?"
        >
          ℹ
        </button>
      </TooltipTrigger>
      <TooltipContent side="top" className="max-w-xs">
        <p className="text-xs font-medium mb-1">{formula.shortDescription}</p>
        <code className="block text-xs font-mono bg-muted/50 rounded p-1 mb-1 break-all">
          {formula.formula}
        </code>
        <p className="text-xs text-muted-foreground">{formula.interpretation}</p>
      </TooltipContent>
    </Tooltip>
  )
}
