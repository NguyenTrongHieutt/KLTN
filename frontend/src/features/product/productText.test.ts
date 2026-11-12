import { describe, expect, it } from 'vitest'
import { newWorkflowUrl } from './productText'

describe("GitHub's new-file link for vibefix.yml (AD-45)", () => {
  it('keeps the slashes of a branch name as path segments and encodes each segment', () => {
    const url = newWorkflowUrl(
      'https://github.com/lan-nguyen/shop-gom/',
      'release/v2 #1',
      '.github/workflows/vibefix.yml',
      'on: push\n',
    )

    expect(url).toBe(
      'https://github.com/lan-nguyen/shop-gom/new/release/v2%20%231' +
        '?filename=.github%2Fworkflows%2Fvibefix.yml&value=on%3A%20push%0A',
    )
  })

  it('leaves a plain branch as it is', () => {
    expect(newWorkflowUrl('https://github.com/lan-nguyen/shop-gom', 'main', 'a.yml', 'x')).toBe(
      'https://github.com/lan-nguyen/shop-gom/new/main?filename=a.yml&value=x',
    )
  })
})
