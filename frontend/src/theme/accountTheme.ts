import { applySystemTheme } from './systemTheme'

export type ThemeChoice = 'LIGHT' | 'DARK' | 'SYSTEM'

let stopFollowingSystem: (() => void) | null = null

/**
 * Applies a theme at once (UX-DR5): "Sáng" and "Tối" set `.dark` on `<html>` for good; "Theo hệ
 * thống" follows `prefers-color-scheme` live, as before sign-in.
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
