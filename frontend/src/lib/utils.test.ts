import { describe, expect, it } from 'vitest'
import { cn } from './utils'

describe('cn', () => {
  it('keeps a DESIGN.md text size next to a text color', () => {
    expect(cn('text-caption font-semibold', 'text-primary')).toBe('text-caption font-semibold text-primary')
    expect(cn('text-label', 'text-muted-foreground')).toBe('text-label text-muted-foreground')
  })

  it('lets a later text size replace an earlier one', () => {
    expect(cn('text-caption', 'text-h3')).toBe('text-h3')
    expect(cn('text-body-sm', 'text-body')).toBe('text-body')
  })
})
