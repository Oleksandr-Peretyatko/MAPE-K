"use client"

import { useState, useMemo, useRef } from "react"
import { ChevronUp, ChevronDown, ChevronsUpDown, Search } from "lucide-react"
import { cn } from "@/lib/utils"

export interface SortableColumn<T> {
  key: string
  label: string
  sortable?: boolean
  /** Custom sort value extractor. Falls back to `row[key]` when omitted. */
  sortValue?: (row: T) => number | string
  render?: (row: T) => React.ReactNode
  className?: string
}

interface SortableTableProps<T> {
  columns: SortableColumn<T>[]
  data: T[]
  defaultSortKey: string
  defaultSortDir?: "asc" | "desc"
  searchPlaceholder?: string
  /** Keys to include in search matching (string-coerces the values). */
  searchKeys?: (keyof T)[]
  rowClassName?: (row: T) => string
  emptyMessage?: string
  /** Rendered above the table when data is present (e.g. summary badges). */
  summary?: React.ReactNode
}

export function SortableTable<T extends object>({
  columns,
  data,
  defaultSortKey,
  defaultSortDir = "desc",
  searchPlaceholder = "Search…",
  searchKeys,
  rowClassName,
  emptyMessage = "No data",
  summary,
}: SortableTableProps<T>) {
  const [sortKey, setSortKey] = useState(defaultSortKey)
  const [sortDir, setSortDir] = useState<"asc" | "desc">(defaultSortDir)
  const [query, setQuery] = useState("")
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [debouncedQuery, setDebouncedQuery] = useState("")

  function handleSearch(v: string) {
    setQuery(v)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => setDebouncedQuery(v), 180)
  }

  function toggleSort(key: string) {
    if (sortKey === key) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"))
    } else {
      setSortKey(key)
      setSortDir("desc")
    }
  }

  const filtered = useMemo(() => {
    if (!debouncedQuery || !searchKeys?.length) return data
    const q = debouncedQuery.toLowerCase()
    return data.filter((row) =>
      searchKeys.some((k) => String((row as Record<keyof T, unknown>)[k] ?? "").toLowerCase().includes(q))
    )
  }, [data, debouncedQuery, searchKeys])

  const sorted = useMemo(() => {
    const col = columns.find((c) => c.key === sortKey)
    return [...filtered].sort((a, b) => {
      const rowA = a as Record<string, unknown>
      const rowB = b as Record<string, unknown>
      const av = col?.sortValue ? col.sortValue(a) : (rowA[sortKey] as number | string ?? 0)
      const bv = col?.sortValue ? col.sortValue(b) : (rowB[sortKey] as number | string ?? 0)
      if (typeof av === "number" && typeof bv === "number") {
        return sortDir === "asc" ? av - bv : bv - av
      }
      return sortDir === "asc"
        ? String(av).localeCompare(String(bv))
        : String(bv).localeCompare(String(av))
    })
  }, [filtered, sortKey, sortDir, columns])

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-3 flex-wrap">
        {summary}
        <div className="relative ml-auto">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground pointer-events-none" />
          <input
            type="text"
            value={query}
            onChange={(e) => handleSearch(e.target.value)}
            placeholder={searchPlaceholder}
            className="h-8 w-52 rounded-md border border-input bg-background pl-8 pr-3 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          />
        </div>
      </div>

      {sorted.length === 0 ? (
        <p className="py-8 text-center text-xs text-muted-foreground">{emptyMessage}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-max text-xs">
            <thead>
              <tr className="border-b border-border">
                {columns.map((col) => (
                  <th
                    key={col.key}
                    className={cn(
                      "px-3 py-2 text-left text-[11px] font-semibold uppercase tracking-wide text-muted-foreground select-none",
                      col.sortable !== false && "cursor-pointer hover:text-foreground transition-colors",
                      col.className,
                    )}
                    onClick={() => col.sortable !== false && toggleSort(col.key)}
                  >
                    <span className="inline-flex items-center gap-1">
                      {col.label}
                      {col.sortable !== false && (
                        sortKey === col.key ? (
                          sortDir === "asc"
                            ? <ChevronUp className="h-3 w-3 text-foreground" />
                            : <ChevronDown className="h-3 w-3 text-foreground" />
                        ) : (
                          <ChevronsUpDown className="h-3 w-3 opacity-30" />
                        )
                      )}
                    </span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sorted.map((row, i) => (
                <tr
                  key={i}
                  className={cn(
                    "border-b border-border/50 hover:bg-muted/30 transition-colors",
                    rowClassName?.(row),
                  )}
                >
                  {columns.map((col) => (
                    <td key={col.key} className={cn("px-3 py-2.5", col.className)}>
                      {col.render ? col.render(row) : String((row as Record<string, unknown>)[col.key] ?? "—")}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
