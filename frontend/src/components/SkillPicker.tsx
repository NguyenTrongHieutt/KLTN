import { Combobox } from '@base-ui/react/combobox'
import { Check, X } from 'lucide-react'
import { useMemo, useRef, useState } from 'react'
import { useCatalog } from '@/features/catalog/catalogApi'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'
import { viNormalize } from '@/lib/viNormalize'

/** A Skill as the picker shows it; `retired` for one the catalog no longer offers (AD-38). */
export interface SkillOption {
  id: string
  name: string
  hint: string
  retired?: boolean
}

interface SkillGroup {
  value: string
  items: SkillOption[]
}

/**
 * The Skill picker (UX-DR75, AD-38): a Base UI Combobox (multiple) over the active catalog, grouped
 * by Skill Category in the Administrator's order. Typing filters with the database's folding
 * (`viNormalize`), so accents and letter case do not matter; while an IME composes a letter (Telex,
 * VNI) the filter waits for `compositionend`. Chosen Skills are removable tokens, and a Skill the
 * catalog retired keeps its token until removed. At `max`, the other options are disabled.
 * `showHints` is for Customer screens (NFR-12).
 */
export function SkillPicker({
  id,
  value,
  onChange,
  known = [],
  max,
  showHints = false,
  describedBy,
  invalid,
}: {
  id: string
  /** The chosen Skill IDs, in order. */
  value: string[]
  onChange: (ids: string[]) => void
  /** Skills already on the record, with their names, retired ones included. */
  known?: { id: string; name: string; active: boolean }[]
  max: number
  showHints?: boolean
  describedBy?: string
  invalid?: boolean
}) {
  const catalog = useCatalog()
  const [input, setInput] = useState('')
  const [query, setQuery] = useState('')
  const composing = useRef(false)

  const groups = useMemo<SkillGroup[]>(
    () =>
      (catalog.data?.categories ?? []).map((category) => ({
        value: category.name,
        items: category.skills.map((skill) => ({ id: skill.id, name: skill.name, hint: skill.hint })),
      })),
    [catalog.data],
  )

  const byId = useMemo(() => {
    const options = new Map<string, SkillOption>()
    for (const skill of known) {
      options.set(skill.id, { id: skill.id, name: skill.name, hint: '', retired: !skill.active })
    }
    for (const group of groups) {
      for (const skill of group.items) {
        options.set(skill.id, skill)
      }
    }
    return options
  }, [groups, known])

  const chosen = value.map((skillId) => byId.get(skillId)).filter((option): option is SkillOption => !!option)
  const atLimit = chosen.length >= max

  const filtered = useMemo(() => {
    const folded = viNormalize(query)
    if (!folded) {
      return groups
    }
    return groups
      .map((group) => ({ ...group, items: group.items.filter((skill) => viNormalize(skill.name).includes(folded)) }))
      .filter((group) => group.items.length > 0)
  }, [groups, query])

  return (
    <Combobox.Root
      multiple
      items={groups}
      filteredItems={filtered}
      value={chosen}
      onValueChange={(next) => onChange(next.map((option) => option.id))}
      inputValue={input}
      onInputValueChange={(next) => {
        setInput(next)
        if (!composing.current) {
          setQuery(next)
        }
      }}
      isItemEqualToValue={(item, selected) => item.id === selected.id}
      itemToStringLabel={(option) => option.name}
    >
      <Combobox.InputGroup
        className={cn(
          'flex min-h-tap-min w-full cursor-text flex-wrap items-center gap-1.5 rounded-md border border-input bg-card px-2 py-1.5 focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-ring',
          invalid && 'border-destructive',
        )}
      >
        <Combobox.Chips
          className="flex w-full flex-wrap items-center gap-1.5"
          aria-label={chosen.length > 0 ? t('screen.shared.skillPicker.chosen') : undefined}
        >
          {chosen.map((skill) => (
            <Combobox.Chip
              key={skill.id}
              aria-description={t('screen.shared.skillPicker.chipHint')}
              className="flex items-center gap-1 rounded-md bg-muted py-0.5 pr-0.5 pl-2 text-body-sm outline-none focus-within:bg-primary-soft data-highlighted:bg-primary-soft"
            >
              <span>{skill.name}</span>
              {skill.retired && <span className="text-caption text-muted-foreground">{t('screen.shared.skillPicker.retired')}</span>}
              <Combobox.ChipRemove
                aria-label={t('screen.shared.skillPicker.remove', { skill: skill.name })}
                className="flex size-6 items-center justify-center rounded-sm hover:bg-card focus-visible:outline-2 focus-visible:outline-ring"
              >
                <X aria-hidden="true" className="size-3.5" />
              </Combobox.ChipRemove>
            </Combobox.Chip>
          ))}
          <Combobox.Input
            id={id}
            aria-describedby={describedBy}
            aria-invalid={invalid || undefined}
            aria-description={
              chosen.length > 0 ? t('screen.shared.skillPicker.inputHint', { n: chosen.length }) : undefined
            }
            placeholder={chosen.length > 0 ? '' : t('screen.shared.skillPicker.placeholder')}
            onCompositionStart={() => {
              composing.current = true
            }}
            onCompositionEnd={(event) => {
              composing.current = false
              setQuery(event.currentTarget.value)
            }}
            className="min-h-8 min-w-24 flex-1 border-0 bg-transparent px-1 text-body outline-none placeholder:text-muted-foreground"
          />
        </Combobox.Chips>
      </Combobox.InputGroup>
      <Combobox.Portal>
        <Combobox.Positioner sideOffset={4} className="z-50">
          <Combobox.Popup className="max-h-[min(var(--available-height),24rem)] w-[var(--anchor-width)] max-w-[var(--available-width)] overflow-y-auto rounded-md border border-border bg-popover py-1 text-popover-foreground shadow-md">
            {atLimit && (
              <p className="px-3 py-2 text-caption text-muted-foreground">{t('screen.shared.skillPicker.limit', { n: max })}</p>
            )}
            {catalog.isPending && (
              <p className="px-3 py-2 text-body-sm text-muted-foreground">{t('screen.shared.skillPicker.loading')}</p>
            )}
            {catalog.isError && (
              <p role="alert" className="px-3 py-2 text-body-sm text-destructive">
                {t('error.unexpected')}
              </p>
            )}
            <Combobox.Empty>
              {catalog.isSuccess && (
                <p className="px-3 py-2 text-body-sm text-muted-foreground">
                  {t('screen.shared.skillPicker.noMatch', { q: input.trim() })}
                </p>
              )}
            </Combobox.Empty>
            <Combobox.List>
              {(group: SkillGroup) => (
                <Combobox.Group key={group.value} items={group.items} className="pb-1">
                  <Combobox.GroupLabel className="px-3 pt-2 pb-1 text-caption font-semibold text-muted-foreground">
                    {group.value}
                  </Combobox.GroupLabel>
                  <Combobox.Collection>
                    {(skill: SkillOption) => {
                      const selected = value.includes(skill.id)
                      return (
                        <Combobox.Item
                          key={skill.id}
                          value={skill}
                          disabled={atLimit && !selected}
                          className="grid cursor-default grid-cols-[1rem_1fr] items-start gap-2 px-3 py-2 text-body-sm outline-none select-none data-disabled:opacity-50 data-highlighted:bg-muted"
                        >
                          <Combobox.ItemIndicator className="col-start-1 pt-0.5">
                            <Check aria-hidden="true" className="size-4 text-primary" />
                          </Combobox.ItemIndicator>
                          <span className="col-start-2 flex flex-col">
                            <span>{skill.name}</span>
                            {showHints && <span className="text-caption text-muted-foreground">{skill.hint}</span>}
                          </span>
                        </Combobox.Item>
                      )
                    }}
                  </Combobox.Collection>
                </Combobox.Group>
              )}
            </Combobox.List>
          </Combobox.Popup>
        </Combobox.Positioner>
      </Combobox.Portal>
    </Combobox.Root>
  )
}
