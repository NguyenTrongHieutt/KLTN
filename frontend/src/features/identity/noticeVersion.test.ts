import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import vi from '@/i18n/vi.json'

describe('the N-10 notice version (NFR-9)', () => {
  it('is the same where PUB-7 shows it and where the backend requires it at sign-up', () => {
    const yaml = readFileSync(path.resolve(import.meta.dirname, '../../../../backend/src/main/resources/application.yaml'), 'utf8')
    const required = /privacy-notice-version:\s*"([^"]+)"/.exec(yaml)?.[1]

    expect(required).toBe(vi.notice.privacy.version)
  })
})

describe('the N-24 notice version (FR-80, NFR-9)', () => {
  it('is the same where the GitHub link shows it and where the backend requires it at the start', () => {
    const notices = readFileSync(
      path.resolve(import.meta.dirname, '../../../../backend/src/main/java/vn/vibefix/identity/Notices.java'),
      'utf8',
    )
    const required = /LINK_GITHUB_VERSION\s*=\s*"([^"]+)"/.exec(notices)?.[1]

    expect(required).toBe(vi.notice.github.version)
  })
})
