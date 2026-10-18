import '@testing-library/jest-dom/vitest'
import { configure } from '@testing-library/react'

// The signed-in screens are lazy routes: their first import in a test file takes longer than 1 s.
configure({ asyncUtilTimeout: 5_000 })

/**
 * jsdom has no matchMedia. Tests get a light, mouse-driven screen by default; a test that needs
 * another answer replaces window.matchMedia itself.
 */
if (!window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList
}
