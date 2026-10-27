import { Dialog } from '@base-ui/react/dialog'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import type { components } from '@/api/schema'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { ApiError, apiClient, unwrap } from '@/lib/apiClient'
import { formatDayMonth } from '@/lib/dates'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { OVERVIEW_QUERY_KEY } from './adminApi'

type Category = components['schemas']['CatalogCategoryView']
type Suggestion = components['schemas']['AdminSkillSuggestionView']

const KEY = ['admin', 'catalog'] as const
const QUEUE_KEY = [...KEY, 'suggestions'] as const

/** Comma- or line-separated values, trimmed, without empties. */
function list(text: string): string[] {
  return text
    .split(/[,\n]/)
    .map((value) => value.trim())
    .filter((value) => value !== '')
}

/** `ALREADY_DECIDED` is the suggestion's own; a name another Skill has is the catalog's. */
function decisionError(failure: unknown): string {
  const reason = failure instanceof ApiError ? failure.problem.reason : undefined
  return problemMessage(failure, { module: reason === 'ALREADY_DECIDED' ? 'admin' : 'catalog' }).text
}

function useDecision<T>(run: (input: T) => Promise<Suggestion[]>, done: string) {
  const queryClient = useQueryClient()
  const toasts = useToasts()
  return useMutation({
    mutationFn: run,
    onSuccess: (queue) => {
      queryClient.setQueryData(QUEUE_KEY, queue)
      void queryClient.invalidateQueries({ queryKey: [...KEY, 'categories'] })
      void queryClient.invalidateQueries({ queryKey: [...KEY, 'skills'] })
      void queryClient.invalidateQueries({ queryKey: OVERVIEW_QUERY_KEY })
      toasts.success(done)
    },
  })
}

/**
 * ADM-12's "Đề xuất" tab (FR-73, Story 16.4): the open Skill suggestions, oldest first, then the decided ones.
 * "Duyệt" creates the Skill through the catalog command, with the name and Category editable and the hint
 * required; "Từ chối" takes a reason. A name an inactive Skill has is flagged, since restoring that Skill may
 * be the better answer.
 */
export function SkillSuggestionsPanel({ categories }: { categories: Category[] }) {
  const ids = useId()
  const queue = useQuery({
    queryKey: QUEUE_KEY,
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/skill-suggestions')),
    retry: false,
  })
  const [approving, setApproving] = useState<Suggestion>()
  const [declining, setDeclining] = useState<Suggestion>()

  return (
    <section aria-labelledby={`${ids}-heading`} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4">
      <h2 id={`${ids}-heading`} className="text-h2">
        {t('screen.admin.catalog.suggestions.heading')}
      </h2>
      <RegionState query={queue} skeleton={<Skeleton className="h-64 w-full" />}>
        {queue.data &&
          (queue.data.length === 0 ? (
            <p className="text-body-sm text-muted-foreground">{t('screen.admin.catalog.suggestions.empty')}</p>
          ) : (
            <ul aria-label={t('screen.admin.catalog.suggestions.heading')} className="flex flex-col divide-y divide-border text-body-sm">
              {queue.data.map((suggestion) => (
                <li key={suggestion.id} className="flex flex-wrap items-start justify-between gap-3 py-3">
                  <div className="flex min-w-0 grow flex-col gap-0.5">
                    <span className="text-label font-semibold break-words">{suggestion.name}</span>
                    <span className="text-caption text-muted-foreground">
                      {t('screen.admin.catalog.suggestions.by', {
                        name: suggestion.suggesterName ?? t('screen.admin.catalog.suggestions.erased'),
                        date: formatDayMonth(suggestion.createdAt),
                      })}
                      {suggestion.categoryName ? ` · ${suggestion.categoryName}` : ''}
                    </span>
                    {suggestion.note && <span className="break-words">{suggestion.note}</span>}
                    {suggestion.inactiveSkill && suggestion.status === 'OPEN' && (
                      <span className="text-caption text-warning">
                        {t('screen.admin.catalog.suggestions.inactive', { name: suggestion.inactiveSkill.name })}
                      </span>
                    )}
                    {suggestion.status !== 'OPEN' && (
                      <span className={cn('text-caption', suggestion.status === 'APPROVED' ? 'text-success' : 'text-muted-foreground')}>
                        {suggestion.status === 'APPROVED'
                          ? t('screen.admin.catalog.suggestions.approved', { date: formatDayMonth(suggestion.decidedAt ?? suggestion.createdAt) })
                          : t('screen.admin.catalog.suggestions.declined', {
                              date: formatDayMonth(suggestion.decidedAt ?? suggestion.createdAt),
                              reason: suggestion.declineReason ?? '',
                            })}
                      </span>
                    )}
                  </div>
                  {suggestion.status === 'OPEN' && (
                    <div className="flex gap-2">
                      <Button type="button" size="compact" onClick={() => setApproving(suggestion)}>
                        {t('screen.admin.catalog.suggestions.approve')}
                      </Button>
                      <Button type="button" variant="secondary" size="compact" onClick={() => setDeclining(suggestion)}>
                        {t('screen.admin.catalog.suggestions.decline')}
                      </Button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          ))}
      </RegionState>
      {approving && <ApproveDialog suggestion={approving} categories={categories} onClose={() => setApproving(undefined)} />}
      {declining && <DeclineDialog suggestion={declining} onClose={() => setDeclining(undefined)} />}
    </section>
  )
}

function ApproveDialog({ suggestion, categories, onClose }: { suggestion: Suggestion; categories: Category[]; onClose: () => void }) {
  const ids = useId()
  const active = categories.filter((category) => category.active)
  const [name, setName] = useState(suggestion.name)
  const [categoryId, setCategoryId] = useState(
    active.some((category) => category.id === suggestion.categoryId) ? (suggestion.categoryId ?? '') : (active[0]?.id ?? ''),
  )
  const [hint, setHint] = useState('')
  const [languages, setLanguages] = useState('')
  const [tools, setTools] = useState('')
  const [error, setError] = useState<string>()
  const approve = useDecision(
    () =>
      unwrap(
        apiClient.POST('/api/v1/admin/skill-suggestions/{suggestionId}/approve', {
          params: { path: { suggestionId: suggestion.id } },
          body: { categoryId, name: name.trim(), hint: hint.trim(), matchLanguages: list(languages), matchTools: list(tools) },
        }),
      ),
    t('screen.admin.catalog.suggestions.approvedToast'),
  )
  return (
    <Dialog.Root open onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-16 left-1/2 z-50 flex max-h-[calc(100dvh-96px)] w-[min(560px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <form
            onSubmit={(event) => {
              event.preventDefault()
              approve.mutate(undefined, { onSuccess: onClose, onError: (failure) => setError(decisionError(failure)) })
            }}
            className="flex flex-col gap-3"
          >
            <Dialog.Title className="text-h2">{t('screen.admin.catalog.suggestions.approveTitle')}</Dialog.Title>
            {suggestion.inactiveSkill && (
              <p className="text-body-sm text-warning">
                {t('screen.admin.catalog.suggestions.inactive', { name: suggestion.inactiveSkill.name })}
              </p>
            )}
            <label htmlFor={`${ids}-name`} className="text-label">
              {t('screen.admin.catalog.name')}
            </label>
            <input id={`${ids}-name`} value={name} maxLength={60} onChange={(event) => setName(event.target.value)} className={inputClass} />
            <label htmlFor={`${ids}-category`} className="text-label">
              {t('screen.admin.catalog.category')}
            </label>
            <select id={`${ids}-category`} value={categoryId} onChange={(event) => setCategoryId(event.target.value)} className={inputClass}>
              {active.map((category) => (
                <option key={category.id} value={category.id}>
                  {category.name}
                </option>
              ))}
            </select>
            <label htmlFor={`${ids}-hint`} className="text-label">
              {t('screen.admin.catalog.hint')}
            </label>
            <textarea id={`${ids}-hint`} value={hint} maxLength={160} onChange={(event) => setHint(event.target.value)} className={inputClass} />
            <label htmlFor={`${ids}-languages`} className="text-label">
              {t('screen.admin.catalog.languages')}
            </label>
            <input id={`${ids}-languages`} value={languages} onChange={(event) => setLanguages(event.target.value)} className={inputClass} />
            <label htmlFor={`${ids}-tools`} className="text-label">
              {t('screen.admin.catalog.tools')}
            </label>
            <input id={`${ids}-tools`} value={tools} onChange={(event) => setTools(event.target.value)} className={inputClass} />
            {error && (
              <p role="alert" className="text-body-sm text-destructive">
                {error}
              </p>
            )}
            <div className="flex justify-end gap-2">
              <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.admin.catalog.back')}</Dialog.Close>
              <Button type="submit" disabled={approve.isPending || name.trim() === '' || hint.trim() === '' || categoryId === ''}>
                {t('screen.admin.catalog.suggestions.approve')}
              </Button>
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function DeclineDialog({ suggestion, onClose }: { suggestion: Suggestion; onClose: () => void }) {
  const ids = useId()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const decline = useDecision(
    () =>
      unwrap(
        apiClient.POST('/api/v1/admin/skill-suggestions/{suggestionId}/decline', {
          params: { path: { suggestionId: suggestion.id } },
          body: { reason: reason.trim() },
        }),
      ),
    t('screen.admin.catalog.suggestions.declinedToast'),
  )
  return (
    <Dialog.Root open onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-16 left-1/2 z-50 flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <form
            onSubmit={(event) => {
              event.preventDefault()
              decline.mutate(undefined, { onSuccess: onClose, onError: (failure) => setError(decisionError(failure)) })
            }}
            className="flex flex-col gap-3"
          >
            <Dialog.Title className="text-h2">{t('screen.admin.catalog.suggestions.declineTitle', { name: suggestion.name })}</Dialog.Title>
            <label htmlFor={`${ids}-reason`} className="text-label">
              {t('screen.admin.catalog.suggestions.reason')}
            </label>
            <textarea id={`${ids}-reason`} value={reason} maxLength={500} onChange={(event) => setReason(event.target.value)} className={inputClass} />
            {error && (
              <p role="alert" className="text-body-sm text-destructive">
                {error}
              </p>
            )}
            <div className="flex justify-end gap-2">
              <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.admin.catalog.back')}</Dialog.Close>
              <Button type="submit" disabled={decline.isPending || reason.trim() === ''}>
                {t('screen.admin.catalog.suggestions.decline')}
              </Button>
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
