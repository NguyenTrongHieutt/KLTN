import type { z } from 'zod'
import { t, type MessageKey } from '@/i18n/t'
import { ApiError } from './apiClient'

/** What a field's error says, for a client rule (zod) or a server constraint code. */
const MESSAGES: Record<string, MessageKey> = {
  required: 'form.required',
  NotBlank: 'form.required',
  NotNull: 'form.required',
  email: 'form.email',
  Email: 'form.email',
  format: 'form.format',
  Pattern: 'form.format',
  tooLong: 'form.tooLong',
  Size: 'form.tooLong',
  range: 'form.range',
  Min: 'form.range',
  Max: 'form.range',
}

export function fieldMessage(code: string): string {
  return t(MESSAGES[code] ?? 'form.format')
}

/** The first error of each field in a zod result, by field name. */
export function zodFieldErrors(error: z.ZodError): Record<string, string> {
  const errors: Record<string, string> = {}
  for (const issue of error.issues) {
    const field = String(issue.path[0] ?? '')
    if (field && !(field in errors)) {
      errors[field] = fieldMessage(typeof issue.message === 'string' ? issue.message : 'format')
    }
  }
  return errors
}

/**
 * The server's `VALIDATION` errors by field name (UX-DR44); an element of a list (`repositories[5]`)
 * belongs to its list. Empty for any other failure.
 */
export function serverFieldErrors(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError) || error.problem.code !== 'VALIDATION') {
    return {}
  }
  const errors: Record<string, string> = {}
  for (const violation of error.problem.errors ?? []) {
    const field = violation.field.replace(/\[\d+\]$/, '')
    if (!(field in errors)) {
      errors[field] = fieldMessage(violation.code)
    }
  }
  return errors
}
