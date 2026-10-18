import '@testing-library/jest-dom/vitest'

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
