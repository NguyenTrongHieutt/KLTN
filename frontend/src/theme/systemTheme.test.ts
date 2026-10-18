import { afterEach, describe, expect, it } from 'vitest'
import { applySystemTheme } from './systemTheme'

/** A matchMedia double whose answer the test changes, notifying the listeners like a browser. */
function darkQuery(matches: boolean) {
  const listeners = new Set<(event: MediaQueryListEvent) => void>()
  const query = {
    matches,
    media: '(prefers-color-scheme: dark)',
    addEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => listeners.add(listener),
    removeEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) => listeners.delete(listener),
  }
  return {
    window: { matchMedia: () => query, document } as unknown as Window,
    change(next: boolean) {
      query.matches = next
      listeners.forEach((listener) => listener({ matches: next } as MediaQueryListEvent))
    },
    listeners,
  }
}

describe('applySystemTheme (UX-DR5)', () => {
  afterEach(() => document.documentElement.classList.remove('dark'))

  it('follows prefers-color-scheme at start and when it changes', () => {
    const screen = darkQuery(true)

    const stop = applySystemTheme(screen.window)
    expect(document.documentElement).toHaveClass('dark')

    screen.change(false)
    expect(document.documentElement).not.toHaveClass('dark')

    screen.change(true)
    expect(document.documentElement).toHaveClass('dark')
    stop()
    expect(screen.listeners.size).toBe(0)
  })

  it('stays light when the system is light', () => {
    applySystemTheme(darkQuery(false).window)

    expect(document.documentElement).not.toHaveClass('dark')
  })
})
