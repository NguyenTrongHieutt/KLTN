const DARK = '(prefers-color-scheme: dark)'

/**
 * Before sign-in the theme follows the system (UX-DR5): `.dark` on `<html>` while the system asks
 * for dark, updated live. Returns a function that stops following it, for when an account's own
 * choice from the Theme toggle takes over.
 */
export function applySystemTheme(win: Window = window): () => void {
  const query = win.matchMedia(DARK)
  const apply = (dark: boolean) => win.document.documentElement.classList.toggle('dark', dark)
  const onChange = (event: MediaQueryListEvent) => apply(event.matches)
  apply(query.matches)
  query.addEventListener('change', onChange)
  return () => query.removeEventListener('change', onChange)
}
