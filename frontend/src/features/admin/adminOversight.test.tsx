import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
}

const jobId = '0192b000-0000-7000-8000-0000000000a1'

const job = {
  id: jobId,
  title: 'Sửa sổ đặt bàn',
  state: 'ACTIVE',
  customerName: 'Nguyễn Thị Lan',
  mentorName: 'Đỗ Mạnh Hùng',
  frozen: false,
  overdue: true,
  updatedAt: '2026-10-21T03:00:00Z',
  milestones: [
    { id: 'm1', number: 1, title: 'Sửa lỗi', state: 'FUNDED', dueOn: '2026-10-20', overdueSince: '2026-10-21T03:00:00Z' },
  ],
}

describe('ADM-5 and ADM-8 (FR-63, FR-68)', () => {
  it('filters overdue Jobs, shows the Milestones, and opens the Job’s Audit Log', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const flags: (string | null)[] = []
    const audited: (string | null)[] = []
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/jobs': (request) => {
        flags.push(new URL(request.url).searchParams.get('flag'))
        return json({ items: [job], total: 1, page: 0, size: 20 })
      },
      [`GET /api/v1/admin/jobs/${jobId}`]: () => json(job),
      'GET /api/v1/admin/audit': (request) => {
        audited.push(new URL(request.url).searchParams.get('jobId'))
        return json({
          items: [
            {
              id: 'e1',
              occurredAt: '2026-10-21T03:00:00Z',
              system: false,
              actorAccountId: 'u1',
              actorName: 'Đỗ Mạnh Hùng',
              action: 'job.milestone_submitted',
              jobId,
              objects: [{ type: 'job.milestone', id: '0192b000-0000-7000-8000-000000000001' }],
            },
          ],
          total: 1,
          page: 0,
          size: 50,
        })
      },
    })
    renderAt('/quan-tri/cong-viec')

    await user.click(await screen.findByRole('button', { name: 'Trễ hạn' }))
    await waitFor(() => expect(flags).toContain('OVERDUE'))
    await user.click(await screen.findByRole('link', { name: /Sửa sổ đặt bàn/ }))
    expect(await screen.findByText('Giai đoạn 1 · Sửa lỗi')).toBeInTheDocument()
    expect(screen.getByText('Trễ hạn từ 21/10')).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Xem nhật ký của công việc' }))
    expect(await screen.findByText('job.milestone_submitted')).toBeInTheDocument()
    expect(audited).toContain(jobId)
    expect(screen.getByText('Đỗ Mạnh Hùng')).toBeInTheDocument()
  })
})
