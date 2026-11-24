import { useState } from 'react'

const KEY = 'vibefix.sidebar.collapsed'

/**
 * Whether this browser last left the sidebar collapsed. A convenience of the device, not of the account: a browser
 * that refuses storage (private window, blocked site data) gets the full sidebar.
 */
export function storedSidebarCollapsed(): boolean {
  try {
    return window.localStorage.getItem(KEY) === '1'
  } catch {
    return false
  }
}

/** The sidebar's collapsed state from 1024 px, and the toggle that also remembers it in this browser. */
export function useSidebarCollapsed(): [boolean, () => void] {
  const [collapsed, setCollapsed] = useState(storedSidebarCollapsed)
  const toggle = () => {
    const next = !collapsed
    setCollapsed(next)
    try {
      window.localStorage.setItem(KEY, next ? '1' : '0')
    } catch {
      // Storage refused: the choice lasts until the page reloads.
    }
  }
  return [collapsed, toggle]
}
