import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const css = readFileSync('src/index.css', 'utf8')
const fonts = readFileSync('src/theme/fonts.css', 'utf8')

/** Every color role of the DESIGN.md frontmatter (2026-10-17), shadcn names and product roles. */
const ROLES = [
  'background', 'foreground', 'card', 'card-foreground', 'popover', 'popover-foreground', 'primary',
  'primary-foreground', 'primary-soft', 'primary-border', 'secondary', 'secondary-foreground', 'muted',
  'muted-foreground', 'accent', 'accent-foreground', 'destructive', 'destructive-foreground', 'destructive-soft',
  'border', 'border-strong', 'input', 'ring', 'success', 'success-foreground', 'success-soft', 'warning',
  'warning-foreground', 'warning-soft', 'info', 'info-soft', 'test-mode', 'test-mode-soft', 'ai', 'ai-soft',
]

function block(selector: string): string {
  const start = css.indexOf(`${selector} {`)
  expect(start, `${selector} block`).toBeGreaterThanOrEqual(0)
  return css.slice(start, css.indexOf('}', start))
}

describe('design tokens (UX-DR3, UX-DR4)', () => {
  it('defines every color role on :root and again under .dark, as a hex value', () => {
    const light = block(':root')
    const dark = block('.dark')
    for (const role of ROLES) {
      expect(light, role).toMatch(new RegExp(`--${role}: #[0-9A-Fa-f]{6};`))
      expect(dark, role).toMatch(new RegExp(`--${role}: #[0-9A-Fa-f]{6};`))
      expect(css, role).toContain(`--color-${role}: var(--${role});`)
    }
  })

  it('keeps the light and dark primary of DESIGN.md', () => {
    expect(block(':root')).toContain('--primary: #0B5CAD;')
    expect(block('.dark')).toContain('--primary: #6EA8F2;')
  })

  it('sets the radii directly, not from a single --radius', () => {
    expect(css).toContain('--radius-sm: 4px;')
    expect(css).toContain('--radius-md: 8px;')
    expect(css).toContain('--radius-lg: 12px;')
    expect(css).toContain('--radius-xl: 16px;')
    expect(css).not.toMatch(/--radius:\s/)
  })

  it('names the layout spacing tokens and the type ramp', () => {
    for (const token of ['gutter-mobile', 'gutter-desktop', 'section-gap', 'header-height', 'tab-bar-height',
      'strip-min-height', 'tap-min', 'control-compact']) {
      expect(css, token).toContain(`--spacing-${token}:`)
    }
    expect(css).toContain('--container-content-max: 1120px;')
    expect(css).toContain('--container-reading-max: 720px;')
    for (const [name, size] of [['display', 32], ['display-mobile', 26], ['h1', 24], ['h2', 20], ['h3', 17],
      ['body', 16], ['body-sm', 14], ['label', 14], ['caption', 12], ['tag', 11], ['mono', 13]] as const) {
      expect(css, name).toContain(`--text-${name}: ${size / 16}rem;`)
    }
  })

  it('self-hosts Be Vietnam Pro in weights 400 to 700, latin and vietnamese only, woff2 only', () => {
    const faces = fonts.match(/@font-face \{[^}]+\}/g) ?? []
    expect(faces).toHaveLength(8)
    for (const weight of [400, 500, 600, 700]) {
      for (const subset of ['latin', 'vietnamese']) {
        expect(fonts).toContain(`be-vietnam-pro-${subset}-${weight}-normal.woff2`)
      }
    }
    expect(faces.every((face: string) => face.includes('unicode-range:'))).toBe(true)
    expect(fonts).not.toMatch(/latin-ext|cyrillic|\.woff'|https?:/)
    expect(css).toContain('@import "./theme/fonts.css";')
    expect(css).toContain("--font-sans: 'Be Vietnam Pro', system-ui, sans-serif;")
  })
})
