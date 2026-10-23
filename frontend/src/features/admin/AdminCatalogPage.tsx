import { Dialog } from '@base-ui/react/dialog'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowUp } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import type { components } from '@/api/schema'
import { PageHeading } from '@/app/PageHeading'
import { usePageTitle } from '@/app/usePageTitle'
import { inputClass } from '@/components/formField-props'
import { RegionState } from '@/components/RegionState'
import { Skeleton } from '@/components/Skeleton'
import { useToasts } from '@/components/toasts'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { apiClient, unwrap } from '@/lib/apiClient'
import { problemMessage } from '@/lib/problemMessage'
import { cn } from '@/lib/utils'
import { SkillSuggestionsPanel } from './SkillSuggestionsPanel'

type Category = components['schemas']['CatalogCategoryView']
type Skill = components['schemas']['CatalogSkillView']

const KEY = ['admin', 'catalog'] as const

/** Comma- or line-separated values, trimmed, without empties. */
function list(text: string): string[] {
  return text
    .split(/[,\n]/)
    .map((value) => value.trim())
    .filter((value) => value !== '')
}

/**
 * ADM-12 (`/quan-tri/ky-nang`, FR-72, UX-DR76, UX-DR69): the Skill Categories in display order, which the
 * picker's groups follow, and the Skills with Category and status chips, a search that ignores accents,
 * and paging. "Ngừng dùng" takes a Skill out of every picker, filter, and suggestion while records keep
 * it; nothing is ever deleted. The tab "Đề xuất" (`?tab=de-xuat`, Story 16.4) decides the Skills people
 * suggested.
 */
export function AdminCatalogPage() {
  usePageTitle(t('screen.admin.catalog.title'))
  const [params] = useSearchParams()
  const suggestions = params.get('tab') === 'de-xuat'
  const categories = useQuery({
    queryKey: [...KEY, 'categories'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/admin/catalog/categories')),
    retry: false,
  })
  return (
    <div className="flex flex-col gap-6 px-gutter-mobile py-section-gap md:px-gutter-desktop">
      <PageHeading>{t('screen.admin.catalog.heading')}</PageHeading>
      <CatalogTabs suggestions={suggestions} />
      <RegionState query={categories} skeleton={<Skeleton className="h-64 w-full" />}>
        {categories.data &&
          (suggestions ? (
            <SkillSuggestionsPanel categories={categories.data} />
          ) : (
            <div className="flex flex-col gap-6 lg:flex-row lg:items-start">
              <Categories categories={categories.data} />
              <Skills categories={categories.data} />
            </div>
          ))}
      </RegionState>
    </div>
  )
}

function CatalogTabs({ suggestions }: { suggestions: boolean }) {
  const tabs = [
    { key: 'catalog', to: '/quan-tri/ky-nang', label: t('screen.admin.catalog.tabCatalog'), current: !suggestions },
    { key: 'suggestions', to: '/quan-tri/ky-nang?tab=de-xuat', label: t('screen.admin.catalog.tabSuggestions'), current: suggestions },
  ]
  return (
    <nav aria-label={t('screen.admin.catalog.heading')} className="flex flex-wrap gap-2">
      {tabs.map((tab) => (
        <Link
          key={tab.key}
          to={tab.to}
          aria-current={tab.current ? 'page' : undefined}
          className={cn(
            'flex min-h-control-compact items-center rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
            tab.current ? 'border-primary bg-primary-soft text-foreground' : 'border-border bg-card text-muted-foreground hover:bg-muted',
          )}
        >
          {tab.label}
        </Link>
      ))}
    </nav>
  )
}

function useCatalogCommand<T>(run: (input: T) => Promise<unknown>, done: string) {
  const queryClient = useQueryClient()
  const toasts = useToasts()
  return useMutation({
    mutationFn: run,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: KEY })
      toasts.success(done)
    },
  })
}

function Categories({ categories }: { categories: Category[] }) {
  const ids = useId()
  const [name, setName] = useState('')
  const [renaming, setRenaming] = useState<Category>()
  const [error, setError] = useState<string>()
  const fail = (failure: unknown) => setError(problemMessage(failure, { module: 'catalog' }).text)
  const create = useCatalogCommand(
    (value: string) => unwrap(apiClient.POST('/api/v1/admin/catalog/categories', { body: { name: value } })),
    t('screen.admin.catalog.categorySaved'),
  )
  const reorder = useCatalogCommand(
    (order: string[]) => unwrap(apiClient.PUT('/api/v1/admin/catalog/categories/order', { body: { ids: order } })),
    t('screen.admin.catalog.reordered'),
  )
  const activate = useCatalogCommand(
    ({ id, active }: { id: string; active: boolean }) =>
      unwrap(
        apiClient.POST('/api/v1/admin/catalog/categories/{categoryId}/active', {
          params: { path: { categoryId: id } },
          body: { active },
        }),
      ),
    t('screen.admin.catalog.categorySaved'),
  )

  function move(index: number, by: number) {
    const order = categories.map((category) => category.id)
    const [moved] = order.splice(index, 1)
    order.splice(index + by, 0, moved)
    reorder.mutate(order, { onError: fail })
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (name.trim() !== '') {
      create.mutate(name.trim(), { onSuccess: () => setName(''), onError: fail })
    }
  }

  return (
    <section aria-labelledby={`${ids}-heading`} className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4 lg:w-[340px] lg:shrink-0">
      <h2 id={`${ids}-heading`} className="text-h2">
        {t('screen.admin.catalog.categories')}
      </h2>
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <ul className="flex flex-col divide-y divide-border text-body-sm">
        {categories.map((category, index) => (
          <li key={category.id} className="flex flex-wrap items-center gap-2 py-2">
            <span className={cn('min-w-0 grow break-words', !category.active && 'text-muted-foreground line-through')}>
              {category.name}
              <span className="ml-1 text-caption text-muted-foreground">
                {t('screen.admin.catalog.activeSkills', { n: category.activeSkills })}
              </span>
            </span>
            <Button
              type="button"
              variant="ghost"
              size="compact"
              aria-label={t('screen.admin.catalog.up', { name: category.name })}
              disabled={index === 0 || reorder.isPending}
              onClick={() => move(index, -1)}
            >
              <ArrowUp aria-hidden="true" className="size-4" />
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="compact"
              aria-label={t('screen.admin.catalog.down', { name: category.name })}
              disabled={index === categories.length - 1 || reorder.isPending}
              onClick={() => move(index, 1)}
            >
              <ArrowDown aria-hidden="true" className="size-4" />
            </Button>
            <Button type="button" variant="secondary" size="compact" onClick={() => setRenaming(category)}>
              {t('screen.admin.catalog.rename')}
            </Button>
            <Button
              type="button"
              variant="secondary"
              size="compact"
              onClick={() => activate.mutate({ id: category.id, active: !category.active }, { onError: fail })}
            >
              {t(category.active ? 'screen.admin.catalog.retire' : 'screen.admin.catalog.restore')}
            </Button>
          </li>
        ))}
      </ul>
      <form onSubmit={submit} className="flex flex-wrap items-end gap-2">
        <div className="flex min-w-0 grow flex-col gap-1">
          <label htmlFor={`${ids}-new`} className="text-label">
            {t('screen.admin.catalog.newCategory')}
          </label>
          <input id={`${ids}-new`} value={name} maxLength={60} onChange={(event) => setName(event.target.value)} className={inputClass} />
        </div>
        <Button type="submit" disabled={create.isPending || name.trim() === ''}>
          {t('screen.admin.catalog.add')}
        </Button>
      </form>
      {renaming && <RenameDialog category={renaming} onClose={() => setRenaming(undefined)} />}
    </section>
  )
}

function RenameDialog({ category, onClose }: { category: Category; onClose: () => void }) {
  const ids = useId()
  const [name, setName] = useState(category.name)
  const [error, setError] = useState<string>()
  const rename = useCatalogCommand(
    (value: string) =>
      unwrap(
        apiClient.PUT('/api/v1/admin/catalog/categories/{categoryId}', {
          params: { path: { categoryId: category.id } },
          body: { name: value },
        }),
      ),
    t('screen.admin.catalog.categorySaved'),
  )
  return (
    <Dialog.Root open onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-16 left-1/2 z-50 flex w-[min(480px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <form
            onSubmit={(event) => {
              event.preventDefault()
              rename.mutate(name.trim(), {
                onSuccess: onClose,
                onError: (failure) => setError(problemMessage(failure, { module: 'catalog' }).text),
              })
            }}
            className="flex flex-col gap-4"
          >
            <Dialog.Title className="text-h2">{t('screen.admin.catalog.renameTitle')}</Dialog.Title>
            <label htmlFor={`${ids}-name`} className="text-label">
              {t('screen.admin.catalog.name')}
            </label>
            <input id={`${ids}-name`} value={name} maxLength={60} onChange={(event) => setName(event.target.value)} className={inputClass} />
            {error && (
              <p role="alert" className="text-body-sm text-destructive">
                {error}
              </p>
            )}
            <div className="flex justify-end gap-2">
              <Dialog.Close render={<Button type="button" variant="secondary" />}>{t('screen.admin.catalog.back')}</Dialog.Close>
              <Button type="submit" disabled={rename.isPending || name.trim() === ''}>
                {t('screen.admin.catalog.save')}
              </Button>
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}

function Skills({ categories }: { categories: Category[] }) {
  const ids = useId()
  const [text, setText] = useState('')
  const [query, setQuery] = useState('')
  const [categoryId, setCategoryId] = useState<string>()
  const [active, setActive] = useState<boolean>()
  const [page, setPage] = useState(0)
  const [editing, setEditing] = useState<Skill | 'new'>()
  const [error, setError] = useState<string>()
  const skills = useQuery({
    queryKey: [...KEY, 'skills', query, categoryId, active, page],
    queryFn: () =>
      unwrap(
        apiClient.GET('/api/v1/admin/catalog/skills', { params: { query: { q: query || undefined, categoryId, active, page } } }),
      ),
    retry: false,
  })
  const toggle = useCatalogCommand(
    ({ id, value }: { id: string; value: boolean }) =>
      unwrap(
        apiClient.POST('/api/v1/admin/catalog/skills/{skillId}/active', { params: { path: { skillId: id } }, body: { active: value } }),
      ),
    t('screen.admin.catalog.skillSaved'),
  )
  const chip = (on: boolean) =>
    cn(
      'min-h-control-compact rounded-full border px-3 text-label focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
      on ? 'border-primary bg-primary-soft text-foreground' : 'border-border bg-card text-muted-foreground hover:bg-muted',
    )
  const pages = Math.max(1, Math.ceil((skills.data?.total ?? 0) / (skills.data?.size ?? 25)))

  return (
    <section aria-labelledby={`${ids}-heading`} className="flex min-w-0 grow flex-col gap-3 rounded-lg border border-border bg-card p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={`${ids}-heading`} className="text-h2">
          {t('screen.admin.catalog.skills')}
        </h2>
        <Button type="button" onClick={() => setEditing('new')}>
          {t('screen.admin.catalog.addSkill')}
        </Button>
      </div>
      <form
        role="search"
        onSubmit={(event) => {
          event.preventDefault()
          setQuery(text.trim())
          setPage(0)
        }}
        className="flex flex-wrap items-end gap-2"
      >
        <div className="flex min-w-0 grow flex-col gap-1">
          <label htmlFor={`${ids}-search`} className="text-label">
            {t('screen.admin.catalog.search')}
          </label>
          <input id={`${ids}-search`} type="search" value={text} onChange={(event) => setText(event.target.value)} className={inputClass} />
        </div>
        <Button type="submit" variant="secondary">
          {t('screen.admin.catalog.find')}
        </Button>
      </form>
      <div role="group" aria-label={t('screen.admin.catalog.categoryFilter')} className="flex flex-wrap gap-2">
        {categories.map((category) => (
          <button
            key={category.id}
            type="button"
            aria-pressed={categoryId === category.id}
            onClick={() => {
              setCategoryId(categoryId === category.id ? undefined : category.id)
              setPage(0)
            }}
            className={chip(categoryId === category.id)}
          >
            {category.name}
          </button>
        ))}
      </div>
      <div role="group" aria-label={t('screen.admin.catalog.statusFilter')} className="flex flex-wrap gap-2">
        {[true, false].map((value) => (
          <button
            key={String(value)}
            type="button"
            aria-pressed={active === value}
            onClick={() => {
              setActive(active === value ? undefined : value)
              setPage(0)
            }}
            className={chip(active === value)}
          >
            {t(value ? 'screen.admin.catalog.inUse' : 'screen.admin.catalog.retired')}
          </button>
        ))}
      </div>
      {error && (
        <p role="alert" className="text-body-sm text-destructive">
          {error}
        </p>
      )}
      <RegionState query={skills} skeleton={<Skeleton className="h-64 w-full" />}>
        {skills.data &&
          (skills.data.items.length === 0 ? (
            <p className="text-body-sm text-muted-foreground">{t('screen.admin.catalog.empty')}</p>
          ) : (
            <ul aria-label={t('screen.admin.catalog.skills')} className="flex flex-col divide-y divide-border text-body-sm">
              {skills.data.items.map((skill) => (
                <li key={skill.id} className="flex flex-wrap items-start justify-between gap-3 py-3">
                  <div className="flex min-w-0 grow flex-col gap-0.5">
                    <span className={cn('text-label font-semibold break-words', !skill.active && 'text-muted-foreground line-through')}>
                      {skill.name}
                    </span>
                    <span className="text-caption text-muted-foreground">
                      {skill.categoryName}
                      {skill.active ? '' : ` · ${t('screen.admin.catalog.retired')}`}
                    </span>
                    <span className="break-words">{skill.hint}</span>
                    {(skill.matchLanguages.length > 0 || skill.matchTools.length > 0) && (
                      <span className="text-caption text-muted-foreground break-words">
                        {t('screen.admin.catalog.matches', { keys: [...skill.matchLanguages, ...skill.matchTools].join(', ') })}
                      </span>
                    )}
                  </div>
                  <div className="flex gap-2">
                    <Button type="button" variant="secondary" size="compact" onClick={() => setEditing(skill)}>
                      {t('screen.admin.catalog.edit')}
                    </Button>
                    <Button
                      type="button"
                      variant="secondary"
                      size="compact"
                      onClick={() =>
                        toggle.mutate(
                          { id: skill.id, value: !skill.active },
                          { onError: (failure) => setError(problemMessage(failure, { module: 'catalog' }).text) },
                        )
                      }
                    >
                      {t(skill.active ? 'screen.admin.catalog.retire' : 'screen.admin.catalog.restore')}
                    </Button>
                  </div>
                </li>
              ))}
            </ul>
          ))}
      </RegionState>
      {pages > 1 && (
        <nav aria-label={t('screen.admin.catalog.pagination')} className="flex items-center justify-between gap-2">
          <Button variant="secondary" size="compact" disabled={page === 0} onClick={() => setPage(page - 1)}>
            {t('screen.admin.catalog.previous')}
          </Button>
          <span className="text-caption">{t('screen.admin.catalog.page', { page: page + 1, pages })}</span>
          <Button variant="secondary" size="compact" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>
            {t('screen.admin.catalog.next')}
          </Button>
        </nav>
      )}
      {editing && (
        <SkillDialog
          skill={editing === 'new' ? undefined : editing}
          categories={categories}
          onClose={() => setEditing(undefined)}
        />
      )}
    </section>
  )
}

function SkillDialog({ skill, categories, onClose }: { skill?: Skill; categories: Category[]; onClose: () => void }) {
  const ids = useId()
  const [name, setName] = useState(skill?.name ?? '')
  const [categoryId, setCategoryId] = useState(skill?.categoryId ?? categories.find((category) => category.active)?.id ?? '')
  const [hint, setHint] = useState(skill?.hint ?? '')
  const [languages, setLanguages] = useState(skill?.matchLanguages.join(', ') ?? '')
  const [tools, setTools] = useState(skill?.matchTools.join(', ') ?? '')
  const [error, setError] = useState<string>()
  const save = useCatalogCommand(
    () => {
      const body = { categoryId, name: name.trim(), hint: hint.trim(), matchLanguages: list(languages), matchTools: list(tools) }
      return skill
        ? unwrap(apiClient.PUT('/api/v1/admin/catalog/skills/{skillId}', { params: { path: { skillId: skill.id } }, body }))
        : unwrap(apiClient.POST('/api/v1/admin/catalog/skills', { body }))
    },
    t('screen.admin.catalog.skillSaved'),
  )
  return (
    <Dialog.Root open onOpenChange={(next) => !next && onClose()}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-foreground/50" />
        <Dialog.Popup className="fixed top-16 left-1/2 z-50 flex max-h-[calc(100dvh-96px)] w-[min(560px,calc(100vw-32px))] -translate-x-1/2 flex-col gap-4 overflow-y-auto rounded-lg bg-popover p-6 text-popover-foreground shadow-md">
          <form
            onSubmit={(event) => {
              event.preventDefault()
              save.mutate(undefined, {
                onSuccess: onClose,
                onError: (failure) => setError(problemMessage(failure, { module: 'catalog' }).text),
              })
            }}
            className="flex flex-col gap-3"
          >
            <Dialog.Title className="text-h2">{t(skill ? 'screen.admin.catalog.editTitle' : 'screen.admin.catalog.addTitle')}</Dialog.Title>
            <label htmlFor={`${ids}-name`} className="text-label">
              {t('screen.admin.catalog.name')}
            </label>
            <input id={`${ids}-name`} value={name} maxLength={60} onChange={(event) => setName(event.target.value)} className={inputClass} />
            <label htmlFor={`${ids}-category`} className="text-label">
              {t('screen.admin.catalog.category')}
            </label>
            <select id={`${ids}-category`} value={categoryId} onChange={(event) => setCategoryId(event.target.value)} className={inputClass}>
              {categories.map((category) => (
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
              <Button type="submit" disabled={save.isPending || name.trim() === '' || hint.trim() === '' || categoryId === ''}>
                {t('screen.admin.catalog.save')}
              </Button>
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
