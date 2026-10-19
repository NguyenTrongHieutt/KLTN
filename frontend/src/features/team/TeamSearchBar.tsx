import { useState, type FormEvent } from 'react'
import { inputClass } from '@/components/formField-props'
import { Button } from '@/components/ui/button'
import { t } from '@/i18n/t'
import { cn } from '@/lib/utils'

/**
 * The Search bar (EXPERIENCE.md): a labeled field with "Tìm". Enter or "Tìm" runs the search and
 * typing alone never does, because Telex and VNI compose letters mid-word. "Tìm" is disabled while
 * the field is blank, and input stops at 100 characters.
 */
export function TeamSearchBar({
  id,
  label,
  helper,
  initial,
  onSearch,
}: {
  id: string
  label: string
  helper: string
  initial: string
  onSearch: (q: string) => void
}) {
  const [value, setValue] = useState(initial)

  function submit(event: FormEvent) {
    event.preventDefault()
    if (value.trim()) {
      onSearch(value.trim())
    }
  }

  return (
    <form role="search" onSubmit={submit} className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-label">
        {label}
      </label>
      <div className="flex gap-2">
        <input
          id={id}
          type="search"
          maxLength={100}
          aria-describedby={`${id}-helper`}
          className={cn(inputClass, 'grow')}
          value={value}
          onChange={(event) => setValue(event.target.value)}
        />
        <Button type="submit" disabled={!value.trim()}>
          {t('screen.team.search')}
        </Button>
      </div>
      <p id={`${id}-helper`} className="text-caption text-muted-foreground">
        {helper}
      </p>
    </form>
  )
}
