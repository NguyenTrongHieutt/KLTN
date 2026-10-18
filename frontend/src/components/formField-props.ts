/** The accessibility attributes of a field's control. */
export function fieldProps(id: string, error?: string, helper?: string) {
  const describedBy = [helper ? `${id}-helper` : null, error ? `${id}-error` : null].filter(Boolean).join(' ')
  return {
    id,
    name: id,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': describedBy || undefined,
  }
}

/** The text input of the design system (44 px on Customer forms). */
export const inputClass =
  'min-h-tap-min w-full rounded-md border border-input bg-card px-3 py-2 text-body focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring aria-invalid:border-destructive'
