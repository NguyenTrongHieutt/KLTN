import { describe, expect, it } from 'vitest'
import fixtures from './vi-normalize.fixtures.json'
import { viNormalize } from './viNormalize'

describe('viNormalize (SG-50)', () => {
  it.each(fixtures)('folds "$input" as the database does', ({ input, folded }) => {
    expect(viNormalize(input)).toBe(folded)
  })
})
