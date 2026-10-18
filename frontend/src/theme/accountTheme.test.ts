import { afterEach, describe, expect, it } from 'vitest'
import { applyTheme, DEFAULT_THEME } from './accountTheme'

/** A window whose system asks for dark. */
const darkSystem = {
  matchMedia: () => ({ matches: true, addEventListener: () => {}, removeEventListener: () => {} }),
  document,
} as unknown as Window

describe('the default theme (UX-DR5, author 2026-10-19)', () => {
  afterEach(() => applyTheme(DEFAULT_THEME))

  it('is light like the frames, even on a dark system', () => {
    document.documentElement.classList.add('dark')

    applyTheme(DEFAULT_THEME, darkSystem)

    expect(DEFAULT_THEME).toBe('LIGHT')
    expect(document.documentElement).not.toHaveClass('dark')
  })

  it('turns dark only when the account chooses it', () => {
    applyTheme('SYSTEM', darkSystem)
    expect(document.documentElement).toHaveClass('dark')

    applyTheme('LIGHT', darkSystem)
    expect(document.documentElement).not.toHaveClass('dark')

    applyTheme('DARK', darkSystem)
    expect(document.documentElement).toHaveClass('dark')
  })
})
