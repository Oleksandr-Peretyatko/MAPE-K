"use client"

import { useState, useEffect, useCallback } from "react"
import { Plus, Trash2, Pencil, Check, X } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { api, type QualityAttributeDef } from "@/lib/api"

export function QualityAttributeEditor() {
  const [qas, setQas] = useState<QualityAttributeDef[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState("")

  // new QA form
  const [newName, setNewName] = useState("")
  const [newDesc, setNewDesc] = useState("")
  const [adding, setAdding] = useState(false)
  const [addError, setAddError] = useState("")

  // inline edit
  const [editId, setEditId] = useState<string | null>(null)
  const [editName, setEditName] = useState("")
  const [editDesc, setEditDesc] = useState("")

  const load = useCallback(async () => {
    try {
      setLoading(true)
      setError("")
      setQas(await api.config.qualityAttributes.list())
    } catch {
      setError("Failed to load quality attributes")
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { load() }, [load])

  async function handleAdd() {
    if (!newName.trim()) { setAddError("Name is required"); return }
    setAdding(true)
    setAddError("")
    try {
      await api.config.qualityAttributes.create(newName.trim(), newDesc.trim())
      setNewName("")
      setNewDesc("")
      await load()
    } catch (e: unknown) {
      setAddError(e instanceof Error ? e.message : "Failed to add")
    } finally {
      setAdding(false)
    }
  }

  async function handleDeactivate(id: string) {
    try {
      await api.config.qualityAttributes.deactivate(id)
      await load()
    } catch {
      setError("Failed to deactivate")
    }
  }

  async function handleReactivate(id: string) {
    try {
      await api.config.qualityAttributes.update(id, { active: true })
      await load()
    } catch {
      setError("Failed to reactivate")
    }
  }

  function startEdit(qa: QualityAttributeDef) {
    setEditId(qa.id)
    setEditName(qa.name)
    setEditDesc(qa.description ?? "")
  }

  async function commitEdit() {
    if (!editId) return
    try {
      await api.config.qualityAttributes.update(editId, { name: editName.trim(), description: editDesc.trim() })
      setEditId(null)
      await load()
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to update")
    }
  }

  return (
    <div className="space-y-4">
      {error && <p className="text-xs text-destructive">{error}</p>}

      {/* Add new QA */}
      <div className="flex flex-wrap gap-2 items-end">
        <div className="flex flex-col gap-1">
          <label className="text-[10px] text-muted-foreground uppercase tracking-wide">Name</label>
          <input
            value={newName}
            onChange={e => setNewName(e.target.value)}
            placeholder="e.g. FAULT_TOLERANCE"
            className="h-8 w-44 rounded-md border border-input bg-background px-2 text-xs focus:outline-none focus:ring-1 focus:ring-ring"
          />
        </div>
        <div className="flex flex-col gap-1">
          <label className="text-[10px] text-muted-foreground uppercase tracking-wide">Description</label>
          <input
            value={newDesc}
            onChange={e => setNewDesc(e.target.value)}
            placeholder="Short description"
            className="h-8 w-64 rounded-md border border-input bg-background px-2 text-xs focus:outline-none focus:ring-1 focus:ring-ring"
          />
        </div>
        <Button size="sm" onClick={handleAdd} disabled={adding} className="gap-1.5 self-end">
          <Plus className="h-3.5 w-3.5" />
          Add QA
        </Button>
        {addError && <p className="self-end text-xs text-destructive">{addError}</p>}
      </div>

      {/* QA list */}
      {loading ? (
        <p className="text-xs text-muted-foreground">Loading…</p>
      ) : (
        <div className="rounded-md border border-border overflow-hidden">
          <table className="w-full text-xs">
            <thead className="bg-muted/40">
              <tr>
                <th className="text-left px-3 py-2 font-medium text-muted-foreground">Name</th>
                <th className="text-left px-3 py-2 font-medium text-muted-foreground">Description</th>
                <th className="text-left px-3 py-2 font-medium text-muted-foreground">Status</th>
                <th className="px-3 py-2" />
              </tr>
            </thead>
            <tbody>
              {qas.map(qa => (
                <tr key={qa.id} className="border-t border-border/50 hover:bg-muted/20">
                  <td className="px-3 py-2 font-mono font-medium">
                    {editId === qa.id ? (
                      <input
                        value={editName}
                        onChange={e => setEditName(e.target.value)}
                        className="h-6 w-32 rounded border border-input bg-background px-1.5 text-xs focus:outline-none focus:ring-1 focus:ring-ring"
                      />
                    ) : (
                      <span className={qa.active ? "" : "opacity-40 line-through"}>{qa.name}</span>
                    )}
                  </td>
                  <td className="px-3 py-2 text-muted-foreground">
                    {editId === qa.id ? (
                      <input
                        value={editDesc}
                        onChange={e => setEditDesc(e.target.value)}
                        className="h-6 w-56 rounded border border-input bg-background px-1.5 text-xs focus:outline-none focus:ring-1 focus:ring-ring"
                      />
                    ) : (
                      <span className={qa.active ? "" : "opacity-40"}>{qa.description}</span>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    <div className="flex gap-1.5">
                      {qa.active ? (
                        <Badge variant="success" className="text-[10px] px-1.5 py-0">Active</Badge>
                      ) : (
                        <Badge variant="outline" className="text-[10px] px-1.5 py-0 text-muted-foreground">Inactive</Badge>
                      )}
                      {qa.builtIn && <Badge variant="secondary" className="text-[10px] px-1.5 py-0">Built-in</Badge>}
                    </div>
                  </td>
                  <td className="px-3 py-2">
                    <div className="flex items-center gap-1 justify-end">
                      {editId === qa.id ? (
                        <>
                          <Button variant="ghost" size="icon" className="h-6 w-6" onClick={commitEdit} title="Save">
                            <Check className="h-3 w-3 text-green-600" />
                          </Button>
                          <Button variant="ghost" size="icon" className="h-6 w-6" onClick={() => setEditId(null)} title="Cancel">
                            <X className="h-3 w-3" />
                          </Button>
                        </>
                      ) : (
                        <>
                          <Button variant="ghost" size="icon" className="h-6 w-6" onClick={() => startEdit(qa)} title="Edit">
                            <Pencil className="h-3 w-3" />
                          </Button>
                          {qa.active ? (
                            <Button variant="ghost" size="icon" className="h-6 w-6" onClick={() => handleDeactivate(qa.id)} title="Deactivate">
                              <Trash2 className="h-3 w-3 text-destructive" />
                            </Button>
                          ) : (
                            <Button variant="ghost" size="sm" className="h-6 px-2 text-[10px]" onClick={() => handleReactivate(qa.id)}>
                              Restore
                            </Button>
                          )}
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
