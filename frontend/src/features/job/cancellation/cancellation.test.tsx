import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

afterEach(() => {
  vi.unstubAllGlobals()
})

const customerJob = {
  id: 'j1',
  productId: 'pr1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  title: 'Sửa sổ đặt bàn',
  jobTypes: ['BUG_FIX'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-18T03:00:00Z',
  version: 12,
  agreementShared: true,
  agreementConfirmed: true,
  canCancelSelection: false,
}

const mentorJob = {
  id: 'j1',
  title: 'Sửa sổ đặt bàn',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  version: 12,
  jobTypes: ['BUG_FIX'],
  selectedAt: '2026-10-18T03:00:00Z',
  codeJob: true,
  commitEmailNoticeAcknowledged: true,
  agreementConfirmed: true,
}

const milestone = (overrides: object = {}) => ({
  milestoneId: 'm1',
  number: 1,
  title: 'Sửa lỗi trùng giờ',
  state: 'FUNDED',
  priceVnd: 800000,
  dueOn: '2026-11-20',
  done: 0,
  total: 1,
  inReview: 0,
  roundsLeft: 2,
  revisionCriteria: null,
  autoAccepted: false,
  refunded: false,
  ...overrides,
})

const progress = (overrides: object = {}) => ({
  boardId: 'j1',
  title: 'Sửa sổ đặt bàn',
  state: 'ACTIVE',
  productName: 'Sổ đặt bàn Quán Mây',
  mentorName: 'Đỗ Mạnh Hùng',
  audience: 'CUSTOMER',
  milestones: [milestone()],
  pages: [],
  crew: [],
  mine: [],
  assessment: false,
  ...overrides,
})

const proposal = (overrides: object = {}) => ({
  id: 'cp1',
  scope: 'REMAINING',
  reason: 'Không còn cần nữa',
  proposer: 'CUSTOMER',
  mine: true,
  status: 'OPEN',
  answerBy: '2026-10-25T03:00:00Z',
  unanswered: false,
  createdAt: '2026-10-20T03:00:00Z',
  decidedAt: null,
  ...overrides,
})

const cancellation = (overrides: object = {}) => ({
  jobId: 'j1',
  audience: 'CUSTOMER',
  state: 'ACTIVE',
  version: 12,
  canPropose: true,
  held: true,
  proposal: null,
  ...overrides,
})

describe('JOB-1 cancellation by agreement (FR-40)', () => {
  it('lets the Customer propose cancelling the remaining Milestones with a reason', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress()),
      'GET /api/v1/boards/j1/cancellation': () => json(cancellation()),
      'POST /api/v1/boards/j1/cancellation': async (request) => {
        sent.push(await request.json())
        return json(cancellation({ canPropose: false, proposal: proposal() }))
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    await user.click(within(section).getByRole('button', { name: 'Đề nghị hủy' }))
    await user.click(within(section).getByRole('radio', { name: 'Hủy các giai đoạn còn lại' }))
    await user.type(within(section).getByLabelText('Lý do'), 'Không còn cần nữa')
    await user.click(within(section).getByRole('button', { name: 'Gửi đề nghị hủy' }))
    await waitFor(() => expect(sent).toEqual([{ scope: 'REMAINING', reason: 'Không còn cần nữa', version: 12 }]))
    expect(await within(section).findByText('Khách hàng đề nghị hủy · Hủy các giai đoạn còn lại')).toBeInTheDocument()
    expect(within(section).getByRole('button', { name: 'Rút đề nghị' })).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: 'Xác nhận hủy' })).toBeNull()
  })

  it('lets the Mentor confirm through a dialog that explains the full refund', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/progress': () => json(progress({ audience: 'MENTOR' })),
      'GET /api/v1/boards/j1/cancellation': () =>
        json(cancellation({ audience: 'MENTOR', canPropose: false, proposal: proposal({ mine: false }) })),
      'POST /api/v1/boards/j1/cancellation/confirm': async (request) => {
        sent.push(await request.json())
        return json(
          cancellation({ audience: 'MENTOR', state: 'CANCELLED', canPropose: false, proposal: proposal({ mine: false, status: 'CONFIRMED' }) }),
        )
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    await user.click(within(section).getByRole('button', { name: 'Xác nhận hủy' }))
    const dialog = await screen.findByRole('dialog', { name: 'Hủy công việc này?' })
    expect(within(dialog).getByText(/được hoàn đủ số tiền đó/)).toBeInTheDocument()
    expect(within(dialog).getByText(/Không hoàn tác được/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận hủy' }))
    await waitFor(() => expect(sent).toEqual([{ version: 12 }]))
  })

  it('reads a refunded cancelled Milestone as cancelled with its money returned', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json({ ...customerJob, state: 'CANCELLED' }),
      'GET /api/v1/boards/j1/progress': () =>
        json(progress({ state: 'CANCELLED', milestones: [milestone({ state: 'CANCELLED', refunded: true })] })),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText('Đã hủy · đã hoàn tiền')).toBeInTheDocument()
  })
})
