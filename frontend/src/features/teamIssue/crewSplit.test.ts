import { describe, expect, it } from 'vitest'
import { crewSplit } from './crewSplit'

const MENTOR = 'mentor'

describe('CrewSplit in the browser (FR-75)', () => {
  it('floors each part and gives the remainder to the Mentor', () => {
    expect(crewSplit(1001, new Map([[MENTOR, 20], ['a', 80]]), MENTOR).get(MENTOR)).toBe(201)
  })

  it('leaves out a zero part', () => {
    const parts = crewSplit(1000, new Map([[MENTOR, 0], ['a', 33], ['b', 67]]), MENTOR)
    expect([...parts.entries()]).toEqual([
      ['a', 330],
      ['b', 670],
    ])
  })
})
