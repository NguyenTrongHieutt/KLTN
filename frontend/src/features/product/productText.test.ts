import { describe, expect, it } from 'vitest'
import { newWorkflowUrl } from './productText'

describe("GitHub's new-file link for vibefix.yml (AD-45)", () => {
  it('keeps the slashes of a branch name as path segments and encodes each segment', () => {
    const url = newWorkflowUrl('https://github.com/lan-nguyen/shop-gom/', 'release/v2 #1', '.github/workflows/vibefix.yml')

    expect(url).toBe(
      'https://github.com/lan-nguyen/shop-gom/new/release/v2%20%231?filename=.github%2Fworkflows%2Fvibefix.yml',
    )
  })

  it('leaves a plain branch as it is', () => {
    expect(newWorkflowUrl('https://github.com/lan-nguyen/shop-gom', 'main', 'a.yml')).toBe(
      'https://github.com/lan-nguyen/shop-gom/new/main?filename=a.yml',
    )
  })

  it('carries no file content, because GitHub refuses a URL that long', () => {
    const url = newWorkflowUrl('https://github.com/lan-nguyen/shop-gom', 'main', 'a.yml')

    expect(url).not.toContain('value=')
    expect(url.length).toBeLessThan(200)
  })
})
