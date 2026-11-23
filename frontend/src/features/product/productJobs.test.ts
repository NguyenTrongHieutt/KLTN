import { describe, expect, it } from 'vitest'
import type { JobSummary } from '@/features/job/jobApi'
import { productJobCounts, productJobsText } from './productJobs'

const job = (state: JobSummary['state'], productId = 'p1') =>
  ({ id: state, title: state, productId, productName: 'P', state, jobTypes: [], updatedAt: '' }) as unknown as JobSummary

describe('a Product\'s Jobs', () => {
  it('counts a Draft as running and a Closed or Cancelled Job as ended, on this Product only', () => {
    expect(productJobCounts([job('DRAFT'), job('ACTIVE'), job('CLOSED'), job('CANCELLED'), job('ACTIVE', 'p2')], 'p1')).toEqual({
      running: 2,
      ended: 2,
    })
  })

  it.each([
    [{ running: 0, ended: 0 }, 'Chưa có công việc'],
    [{ running: 2, ended: 0 }, '2 công việc đang chạy'],
    [{ running: 0, ended: 1 }, '1 công việc đã kết thúc'],
    [{ running: 1, ended: 3 }, '1 đang chạy · 3 đã kết thúc'],
  ])('says %o as "%s"', (counts, text) => {
    expect(productJobsText(counts)).toBe(text)
  })
})
