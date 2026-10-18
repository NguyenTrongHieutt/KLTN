import { applySystemTheme } from './systemTheme'

export type ThemeChoice = 'LIGHT' | 'DARK' | 'SYSTEM'

/**
 * The light theme of the UI frames, before sign-in and for an account that has not chosen (author,
 * 2026-10-19): dark only when the account picks "Tối", or "Theo hệ thống" on a dark system.
 */
export const DEFAULT_THEME: ThemeChoice = 'LIGHT'

let stopFollowingSystem: (() => void) | null = null

/**
 * Applies a theme at once (UX-DR5): "Sáng" and "Tối" set `.dark` on `<html>` for good; "Theo hệ
 * thống" follows `prefers-color-scheme` live.
 */
export function applyTheme(choice: ThemeChoice, win: Window = window): void {
  stopFollowingSystem?.()
  stopFollowingSystem = null
  if (choice === 'SYSTEM') {
    stopFollowingSystem = applySystemTheme(win)
    return
  }
  win.document.documentElement.classList.toggle('dark', choice === 'DARK')
}
