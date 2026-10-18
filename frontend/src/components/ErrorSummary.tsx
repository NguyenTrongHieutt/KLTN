import { useEffect, useRef } from 'react'
import { t } from '@/i18n/t'

/**
 * The error summary at the top of a form (UX-DR44): how many fields need attention, each linking to
 * its field. It takes focus when it appears or its list changes, so a screen reader hears it first.
 */
export function ErrorSummary({ errors }: { errors: { id: string; label: string; message: string }[] }) {
  const summary = useRef<HTMLDivElement>(null)
  const ids = errors.map((error) => error.id).join(' ')
  useEffect(() => {
    if (ids) {
      summary.current?.focus()
    }
  }, [ids])
  if (errors.length === 0) {
    return null
  }
  return (
    <div
      ref={summary}
      role="alert"
      tabIndex={-1}
      className="flex flex-col gap-2 rounded-md border-l-4 border-destructive bg-destructive-soft p-4 focus:outline-none"
    >
      <p className="text-label">{t('error.VALIDATION', { n: errors.length })}</p>
      <ul className="flex list-disc flex-col gap-1 pl-5 text-body-sm">
        {errors.map((error) => (
          <li key={error.id}>
            <a
              href={`#${error.id}`}
              className="text-destructive underline-offset-4 hover:underline"
              onClick={(event) => {
                event.preventDefault()
                document.getElementById(error.id)?.focus()
              }}
            >
              {error.label}: {error.message}
            </a>
          </li>
        ))}
      </ul>
    </div>
  )
}
