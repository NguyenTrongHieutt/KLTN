import { describe, expect, it } from 'vitest'
import { showsDeadlineChip, todoText } from './texts'

describe("a to-do's deadline chip", () => {
  const deadlineAt = '2026-12-01T03:00:00Z'

  it('stands beside a sentence that says no date', () => {
    expect(showsDeadlineChip({ params: { n: '1' }, deadlineAt, deadlineNear: false })).toBe(true)
  })

  it('does not repeat a date the sentence already says, unless the deadline is near', () => {
    expect(showsDeadlineChip({ params: { n: '1', date: '01/12' }, deadlineAt, deadlineNear: false })).toBe(false)
    expect(showsDeadlineChip({ params: { n: '1', date: '01/12' }, deadlineAt, deadlineNear: true })).toBe(true)
  })

  it('is absent without a deadline', () => {
    expect(showsDeadlineChip({ params: {}, deadlineNear: false })).toBe(false)
  })
})

describe("a to-do's sentence", () => {
  it('says "công việc này" for a Job\'s to-do, which is always read beside the Job\'s name', () => {
    expect(todoText({ key: 'repo.confirm_source_clean', jobId: 'j1', params: { job: 'Sửa lỗi đặt bàn' } })).toBe(
      'Xác nhận repo nguồn của công việc này đã sạch secret',
    )
    expect(todoText({ key: 'job.answer_closure', jobId: 'j1', params: { job: 'Sửa lỗi đặt bàn', date: '01/12' } })).toBe(
      'Xác nhận kết thúc công việc này · trước 01/12',
    )
  })

  it('keeps the title when no Job stands beside the to-do, and every other parameter as it came', () => {
    expect(todoText({ key: 'matching.sign_nda', params: { job: 'Sửa lỗi đặt bàn' } })).toBe(
      'Ký cam kết bảo mật cho Sửa lỗi đặt bàn',
    )
    expect(todoText({ key: 'delivery.hand_over', jobId: 'j1', params: { n: '2' } })).toBe('Bàn giao Giai đoạn 2')
  })
})
