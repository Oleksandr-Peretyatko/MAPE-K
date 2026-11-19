import { Inbox } from "lucide-react"
import { cn } from "@/lib/utils"

interface EmptyStateProps {
  title?: string
  description?: string
  icon?: React.ReactNode
  className?: string
}

export function EmptyState({ title = "No data", description, icon, className }: EmptyStateProps) {
  return (
    <div className={cn("flex flex-col items-center justify-center gap-3 py-16 text-center", className)}>
      <div className="rounded-full bg-muted p-4 text-muted-foreground">
        {icon ?? <Inbox className="h-6 w-6" />}
      </div>
      <p className="text-sm font-medium text-foreground">{title}</p>
      {description && <p className="max-w-xs text-xs text-muted-foreground">{description}</p>}
    </div>
  )
}
