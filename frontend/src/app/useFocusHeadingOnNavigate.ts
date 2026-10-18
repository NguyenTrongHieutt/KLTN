import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router'

/**
 * After a route change, focus moves to the new page's `h1` inside `main`, so a screen reader starts
 * at the new content (Accessibility Floor). The first load keeps the browser's own focus.
 */
export function useFocusHeadingOnNavigate(mainId: string) {
  const { pathname } = useLocation()
  const firstPath = useRef(pathname)

  useEffect(() => {
    if (pathname === firstPath.current) {
      return
    }
    firstPath.current = ''
    document.querySelector<HTMLElement>(`#${mainId} h1`)?.focus()
  }, [pathname, mainId])
}
