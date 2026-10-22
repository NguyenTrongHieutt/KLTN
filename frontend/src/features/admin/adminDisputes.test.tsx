import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

const row = {
  id: 'd1',
  jobId: 'j1',
  jobTitle: 'Đánh giá sổ đặt bàn',
  openedBy: 'CUSTOMER',
  openedAt: '2026-10-20T03:00:00Z',
  status: 'OPEN',
  assigned: false,
  assignedToMe: false,
  executionStatus: null,
}

const detail = (overrides: object = {}) => ({
  id: 'd1',
  jobId: 'j1',
  jobTitle: 'Đánh giá sổ đặt bàn',
  openedBy: 'CUSTOMER',
  reason: 'Giai đoạn chưa đạt',
  concern: 'HELD_MILESTONE',
  openedAt: '2026-10-20T03:00:00Z',
  status: 'OPEN',
  assigned: false,
  assignedToMe: false,
  held: { milestoneId: 'm1', number: 1, title: 'Báo cáo', releasableVnd: 800000 },
  plannedRemains: true,
  decision: null,
  execution: null,
  statements: [],
  statementDueAt: '2026-10-25T03:00:00Z',
  decidable: true,
  ...overrides,
})

const overview = {
  failedOperations: { count: 0 },
  stalledEvents: { count: 0 },
  activeAdministrators: 2,
  studentCards: { count: 0 },
  mentorApplications: { count: 0 },
  disputes: { count: 1, oldestAt: '2026-10-20T03:00:00Z' },
}

describe('ADM-4 Dispute decisions (FR-54, UX-DR58)', () => {
  it('takes the Dispute and records a split decision through the step-up', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    let current = detail()
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/disputes': () => json([row]),
      'GET /api/v1/admin/disputes/d1': () => json(current),
      'POST /api/v1/admin/disputes/d1/take': () => {
        current = detail({ assigned: true, assignedToMe: true })
        return json(current)
      },
      'POST /api/v1/admin/disputes/d1/decision': async (request) => {
        sent.push(await request.json())
        current = detail({
          assigned: true,
          assignedToMe: true,
          status: 'DECIDED',
          decision: { releaseVnd: 500000, refundVnd: 300000, nextState: 'CANCEL', reasoning: 'Hai bên cùng có lỗi', decidedAt: '2026-10-21T03:00:00Z' },
          execution: { status: 'DONE', step: 4, failure: null },
        })
        return json(current)
      },
    })
    renderAt('/quan-tri/tranh-chap/d1')

    expect(await screen.findByText(/Giai đoạn 1: Báo cáo · đang giữ/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Nhận xử lý' }))
    await user.type(await screen.findByLabelText('Giải ngân cho nhóm thực hiện (VND)'), '500000')
    await user.type(screen.getByLabelText('Hoàn lại cho khách hàng (VND)'), '200000')
    expect(screen.getByText(/Chưa khớp số tiền đang giữ/)).toBeInTheDocument()
    await user.clear(screen.getByLabelText('Hoàn lại cho khách hàng (VND)'))
    await user.type(screen.getByLabelText('Hoàn lại cho khách hàng (VND)'), '300000')
    expect(screen.getByText(/Khớp số tiền đang giữ/)).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'Hủy' }))
    await user.type(screen.getByLabelText('Lý do quyết định'), 'Hai bên cùng có lỗi')
    await user.click(screen.getByRole('button', { name: 'Xem lại và ghi quyết định' }))
    await user.click(screen.getByRole('button', { name: 'Ghi quyết định' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByRole('textbox'), '123456')
    await user.click(within(dialog).getByRole('button', { name: 'Ghi quyết định' }))
    await waitFor(() =>
      expect(sent).toEqual([
        { releaseVnd: 500000, refundVnd: 300000, nextState: 'CANCEL', reasoning: 'Hai bên cùng có lỗi', stepUpCode: '123456' },
      ]),
    )
    expect(await screen.findByText(/Bước 4\/4 · Đã thực hiện xong/)).toBeInTheDocument()
  })

  it('retries a failed execution', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let retried = false
    const failed = {
      assigned: true,
      assignedToMe: true,
      decision: { releaseVnd: 500000, refundVnd: 300000, nextState: 'END', reasoning: 'Lý do', decidedAt: '2026-10-21T03:00:00Z' },
      execution: { status: 'EXECUTION_FAILED', step: 1, failure: 'IllegalStateException: refund' },
    }
    let current = detail(failed)
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/disputes': () => json([{ ...row, executionStatus: 'EXECUTION_FAILED' }]),
      'GET /api/v1/admin/disputes/d1': () => json(current),
      'POST /api/v1/admin/disputes/d1/retry': () => {
        retried = true
        current = detail({ ...failed, status: 'DECIDED', execution: { status: 'DONE', step: 4, failure: null } })
        return json(current)
      },
    })
    renderAt('/quan-tri/tranh-chap/d1')

    expect(await screen.findByText(/Lỗi: IllegalStateException: refund/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Thử lại' }))
    await waitFor(() => expect(retried).toBe(true))
    expect(await screen.findByText(/Đã thực hiện xong/)).toBeInTheDocument()
  })
})
