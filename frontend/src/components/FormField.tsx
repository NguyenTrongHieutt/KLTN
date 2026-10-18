import { CircleAlert } from 'lucide-react'
import type { ReactNode } from 'react'

/**
 * A form field (UX-DR44): the label above, an optional helper caption, and the error caption with
 * its icon. `children` is the control; give it `id`, `aria-invalid`, and `aria-describedby` from
 * `fieldProps` (`./formField-props`).
 */
export function FormField({
  id,
  label,
  helper,
  error,
  children,
}: {
  id: string
  label: string
  helper?: string
  error?: string
  children: ReactNode
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-label">
        {label}
      </label>
      {children}
      {helper && (
        <p id={`${id}-helper`} className="text-caption text-muted-foreground">
          {helper}
        </p>
      )}
      {error && (
        <p id={`${id}-error`} className="flex items-center gap-1 text-caption text-destructive">
          <CircleAlert aria-hidden="true" className="size-3.5 shrink-0" />
          {error}
        </p>
      )}
    </div>
  )
}
