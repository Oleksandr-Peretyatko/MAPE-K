"use client"

import React, { createContext, useCallback, useContext, useState } from "react"

interface SnapshotContextValue {
  snapshotId: string | null
  setSnapshotId: (id: string | null) => void
  iterationId: string | null
  setIterationId: (id: string | null) => void
  codeVersionId: string | null
  setCodeVersionId: (id: string | null) => void
  navRefreshSeq: number
  triggerNavRefresh: () => void
}

const SnapshotContext = createContext<SnapshotContextValue>({
  snapshotId: null,
  setSnapshotId: () => {},
  iterationId: null,
  setIterationId: () => {},
  codeVersionId: null,
  setCodeVersionId: () => {},
  navRefreshSeq: 0,
  triggerNavRefresh: () => {},
})

export function SnapshotProvider({ children }: { children: React.ReactNode }) {
  const [snapshotId, setSnapshotId] = useState<string | null>(null)
  const [iterationId, setIterationId] = useState<string | null>(null)
  const [codeVersionId, setCodeVersionId] = useState<string | null>(null)
  const [navRefreshSeq, setNavRefreshSeq] = useState(0)
  const triggerNavRefresh = useCallback(() => setNavRefreshSeq(s => s + 1), [])

  return (
    <SnapshotContext.Provider value={{
      snapshotId, setSnapshotId,
      iterationId, setIterationId,
      codeVersionId, setCodeVersionId,
      navRefreshSeq, triggerNavRefresh,
    }}>
      {children}
    </SnapshotContext.Provider>
  )
}

export function useSnapshot() {
  return useContext(SnapshotContext)
}
