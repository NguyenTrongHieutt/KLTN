const DARK = '(prefers-color-scheme: dark)'

/**
 * "Theo hệ thống" (UX-DR5): `.dark` on `<html>` while the system asks for dark, updated live.
 * Returns a function that stops following it, for when the account chooses another theme.
 */
export function applySystemTheme(win: Window = window): () => void {
  const query = win.matchMedia(DARK)
  const apply = (dark: boolean) => win.document.documentElement.classList.toggle('dark', dark)
  const onChange = (event: MediaQueryListEvent) => apply(event.matches)
  apply(query.matches)
  query.addEventListener('change', onChange)
  return () => query.removeEventListener('change', onChange)
}
