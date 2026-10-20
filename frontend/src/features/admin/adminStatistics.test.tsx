import { render, screen, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import type { Session } from '@/features/identity/api'
import { fakeBackend, json } from '@/test/fakeBackend'

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
  account: { id: '00000000-0000-7000-8000-00000000a001', role: 'ADMINISTRATOR', theme: 'LIGHT' },
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
  studentCards: { count: 0 },
  mentorApplications: { count: 0 },
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
            { group: 'usersByRole', counts: [{ key: 'CUSTOMER', value: 12 }, { key: 'MENTOR', value: 3 }] },
            { group: 'acceptances', counts: [{ key: 'AUTOMATIC', value: 2 }, { key: 'MANUAL', value: 5 }] },
            { group: 'privateFeedback', counts: [] },
          ],
        }),
    })
    renderAt('/quan-tri/thong-ke')

    const users = await screen.findByRole('region', { name: 'Người dùng theo vai trò' })
    expect(within(users).getByText('Khách hàng')).toBeInTheDocument()
    expect(within(users).getByText('12')).toBeInTheDocument()
    const acceptances = screen.getByRole('region', { name: 'Nghiệm thu' })
    expect(within(acceptances).getByText('Tự động')).toBeInTheDocument()
    expect(screen.getByText(/Chưa đủ 5 đánh giá kín từ 3 người hướng dẫn/)).toBeInTheDocument()
  })
})
