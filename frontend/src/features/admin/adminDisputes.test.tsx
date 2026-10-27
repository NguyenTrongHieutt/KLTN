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
  evidenceReadable: false,
  evidenceSnapshot: null,
  ...overrides,
})

const overview = {
  failedOperations: { count: 0 },
  stalledEvents: { count: 0 },
  activeAdministrators: 2,
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
    expect(screen.getByText('Nhận xử lý để xem hồ sơ bằng chứng.')).toBeInTheDocument()
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

  it('shows the assignee the Evidence Package, one audited tab at a time', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/disputes': () => json([{ ...row, assigned: true, assignedToMe: true }]),
      'GET /api/v1/admin/disputes/d1': () => json(detail({ assigned: true, assignedToMe: true, evidenceReadable: true })),
      'GET /api/v1/admin/disputes/d1/evidence/agreement': () =>
        json([
          {
            versionNo: 1,
            approvedAt: '2026-10-01T03:00:00Z',
            confirmedAt: '2026-10-01T05:00:00Z',
            scope: 'Đánh giá sổ đặt bàn',
            priceVnd: 800000,
            milestones: [
              {
                milestoneId: 'm1',
                number: 1,
                title: 'Báo cáo',
                priceVnd: 800000,
                dueOn: '2026-11-20',
                criteria: [{ criterionId: 'c1', number: 1, statement: 'Báo cáo nêu đủ lỗi', checkSteps: 'Đọc' }],
              },
            ],
          },
        ]),
      'GET /api/v1/admin/disputes/d1/evidence/payments': () =>
        json([
          {
            milestoneId: 'm1',
            number: 1,
            title: 'Báo cáo',
            moneyIn: [{ type: 'FUND', bucket: 'MILESTONE', amountVnd: 800000, at: '2026-10-02T03:00:00Z' }],
            refunds: [],
            releasedTotalVnd: 0,
            releasableVnd: 800000,
          },
        ]),
      'GET /api/v1/jobs/j1/chat/messages': () =>
        json({
          items: [
            {
              id: 'x1',
              author: 'Đỗ Mạnh Hùng',
              mine: false,
              body: 'Bản sửa đã gửi hôm qua',
              maskedCredentials: 0,
              maskedPersonal: 0,
              sentAt: '2026-10-19T03:00:00Z',
            },
          ],
          hasOlder: false,
        }),
    })
    renderAt('/quan-tri/tranh-chap/d1')

    expect(await screen.findByText('Mỗi lần xem hồ sơ được ghi vào nhật ký kiểm toán.')).toBeInTheDocument()
    expect(await screen.findByText('Báo cáo nêu đủ lỗi')).toBeInTheDocument()
    expect(screen.getAllByRole('tab')).toHaveLength(9)
    expect(seen.some((request) => request.url.includes('/evidence/payments'))).toBe(false)
    await user.click(screen.getByRole('tab', { name: 'Thanh toán' }))
    expect(await screen.findByText(/Đã giải ngân/)).toBeInTheDocument()
    expect(screen.getByText(/Nạp tiền Giai đoạn/)).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Trò chuyện' }))
    expect(await screen.findByText('Bản sửa đã gửi hôm qua')).toBeInTheDocument()
    expect(seen.some((request) => request.url.includes('crew-chat'))).toBe(false)
  })

  it('shows any Administrator what a decided Dispute was decided on', { timeout: 20_000 }, async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/disputes': () => json([{ ...row, status: 'DECIDED', executionStatus: 'DONE' }]),
      'GET /api/v1/admin/disputes/d1': () =>
        json(
          detail({
            status: 'DECIDED',
            assigned: true,
            decision: { releaseVnd: 500000, refundVnd: 300000, nextState: 'END', reasoning: 'Lý do', decidedAt: '2026-10-21T03:00:00Z' },
            execution: { status: 'DONE', step: 4, failure: null },
            evidenceSnapshot: {
              takenAt: '2026-10-21T03:00:00Z',
              agreementVersions: [1, 2],
              reports: [
                { handoverId: 'h1', milestoneNumber: 1, round: 1, status: 'REVISION_REQUESTED', criteria: 3, reportedMet: 3, checkedMet: 1 },
              ],
              workItems: [{ workItemId: 'w1', number: 1, version: 4, status: 'DONE' }],
              pages: [{ pageId: 'p1', versionNo: 2 }],
              jobChat: { messageCount: 14, lastMessageId: 'x14', lastSentAt: '2026-10-20T03:00:00Z' },
              payments: [
                { milestoneId: 'm1', number: 1, moneyInVnd: 800000, refundsVnd: 0, releasedTotalVnd: 0, releasableVnd: 800000 },
              ],
              driftWarnings: [],
              statements: [{ side: 'MENTOR', submittedAt: '2026-10-20T05:00:00Z' }],
            },
          }),
        ),
    })
    renderAt('/quan-tri/tranh-chap/d1')

    expect(await screen.findByRole('heading', { name: 'Bằng chứng khi quyết định' })).toBeInTheDocument()
    expect(screen.getByText('Thỏa thuận: phiên bản 1, 2')).toBeInTheDocument()
    expect(screen.getByText(/Trò chuyện: 14 tin nhắn/)).toBeInTheDocument()
    expect(screen.getByText(/khách hàng xác nhận đạt 1\/3/)).toBeInTheDocument()
    expect(screen.getByText(/Hồ sơ bằng chứng chỉ mở cho quản trị viên nhận xử lý/)).toBeInTheDocument()
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
