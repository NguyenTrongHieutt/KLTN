import { render, screen, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import type { Session } from '@/features/identity/api'
import { administratorAccount, fakeBackend, json } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const minutes = (n: number) => new Date(Date.now() + n * 60_000).toISOString()

const administrator: Session = {
  status: 'SIGNED_IN',
  privacyNoticeVersion: '2.0',
  account: administratorAccount,
  administrator: {
    email: 'phuong@vibefix.vn',
    displayName: 'Hà Phương',
    idleExpiresAt: minutes(30),
    absoluteExpiresAt: minutes(480),
  },
}

afterEach(() => {
  vi.unstubAllGlobals()
})

const overview = {
  failedOperations: { count: 0 },
  stalledEvents: { count: 0 },
  activeAdministrators: 2,
  disputes: { count: 0 },
  lowFeedback: { count: 0 },
  failedExecutions: { count: 0 },
  falsePositives: { count: 0 },
}

describe('ADM-9 statistics (FR-62)', () => {
  it('shows each group with its labels and keeps the private-feedback figure back', { timeout: 30_000 }, async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/statistics': () =>
        json({
          groups: [
            {
              group: 'usersByVerification',
              counts: [
                { key: 'GITHUB', value: 9, unit: 'COUNT' },
                { key: 'LINKEDIN', value: 0, unit: 'COUNT' },
                { key: 'STUDENT', value: 4, unit: 'COUNT' },
              ],
            },
            {
              group: 'usersByJobRole',
              counts: [
                { key: 'CREW', value: 6, unit: 'COUNT' },
                { key: 'CUSTOMER', value: 12, unit: 'COUNT' },
                { key: 'MENTOR', value: 3, unit: 'COUNT' },
              ],
            },
            {
              group: 'acceptances',
              counts: [
                { key: 'AUTOMATIC', value: 2, unit: 'COUNT' },
                { key: 'MANUAL', value: 5, unit: 'COUNT' },
              ],
            },
            { group: 'privateFeedback', counts: [] },
          ],
        }),
    })
    renderAt('/quan-tri/thong-ke')

    // Story 20.3 (UX-DR110): users by Verification and by the roles they hold per Job, with no row per role on the account.
    const verified = await screen.findByRole('region', { name: 'Người dùng theo xác minh' })
    expect(within(verified).getByText('Đã liên kết GitHub')).toBeInTheDocument()
    expect(within(verified).getByText('9')).toBeInTheDocument()
    expect(within(verified).getByText('Đã xác minh LinkedIn')).toBeInTheDocument()
    expect(within(verified).getByText('Sinh viên đã xác minh')).toBeInTheDocument()
    const roles = screen.getByRole('region', { name: 'Người dùng theo vai trò trong công việc' })
    expect(within(roles).getByText('Khách hàng')).toBeInTheDocument()
    expect(within(roles).getByText('12')).toBeInTheDocument()
    expect(within(roles).getByText('Người hướng dẫn')).toBeInTheDocument()
    expect(within(roles).getByText('Thành viên nhóm')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Người dùng theo vai trò' })).not.toBeInTheDocument()
    const acceptances = screen.getByRole('region', { name: 'Nghiệm thu' })
    expect(within(acceptances).getByText('Tự động')).toBeInTheDocument()
    expect(screen.getByText(/Chưa đủ 5 đánh giá kín từ 3 người hướng dẫn/)).toBeInTheDocument()
  })
})

describe('ADM-9 rates and averages (FR-62, Story 17.2)', () => {
  it('reads rates as percentages and averages out of 5, and names nobody', { timeout: 30_000 }, async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/statistics': () =>
        json({
          groups: [
            {
              group: 'rates',
              counts: [
                { key: 'COMPLETION', value: 750, unit: 'PERCENT_TENTHS' },
                { key: 'ON_TIME', value: 500, unit: 'PERCENT_TENTHS' },
              ],
            },
            {
              group: 'workItemReviews',
              counts: [
                { key: 'FIRST_PASS', value: 2, unit: 'COUNT' },
                { key: 'FIRST_PASS_SHARE', value: 667, unit: 'PERCENT_TENTHS' },
                { key: 'RETURNED', value: 1, unit: 'COUNT' },
              ],
            },
            {
              group: 'ratings',
              counts: [
                { key: 'CUSTOMER_TO_MENTOR_AVERAGE', value: 45, unit: 'AVERAGE_TENTHS' },
                { key: 'CUSTOMER_TO_MENTOR_COUNT', value: 2, unit: 'COUNT' },
              ],
            },
            { group: 'teamIssuesByOutcome', counts: [{ key: 'ESCALATED', value: 1, unit: 'COUNT' }] },
          ],
        }),
    })
    renderAt('/quan-tri/thong-ke')

    const rates = await screen.findByRole('region', { name: 'Tỷ lệ công việc' })
    expect(within(rates).getByText('Hoàn thành (trên số công việc đã kết thúc sau thỏa thuận)')).toBeInTheDocument()
    expect(within(rates).getByText('75,0 %')).toBeInTheDocument()
    expect(within(rates).getByText('50,0 %')).toBeInTheDocument()
    const reviews = screen.getByRole('region', { name: 'Việc được duyệt' })
    expect(within(reviews).getByText('66,7 %')).toBeInTheDocument()
    const ratings = screen.getByRole('region', { name: 'Đánh giá công khai' })
    expect(within(ratings).getByText('Khách hàng chấm người hướng dẫn: trung bình (sao)')).toBeInTheDocument()
    expect(within(ratings).getByText('4,5')).toBeInTheDocument()
    const issues = screen.getByRole('region', { name: 'Khiếu nại nội bộ theo kết quả' })
    expect(within(issues).getByText('Đã chuyển quản trị viên')).toBeInTheDocument()
  })
})
