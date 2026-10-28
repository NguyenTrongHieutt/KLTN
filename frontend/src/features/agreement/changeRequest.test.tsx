import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { redirectTo } from '@/lib/redirect'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'

vi.mock('@/lib/redirect', () => ({ redirectTo: vi.fn() }))

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

const job = {
  id: 'j1',
  productId: 'p1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  jobTypes: ['BUG_FIX'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-18T03:00:00Z',
  version: 7,
  mentor: { displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm' },
  canCancelSelection: false,
  agreementShared: true,
  agreementConfirmed: true,
}

const content = {
  scope: 'Sửa lỗi hai khách đặt trùng bàn.',
  priceVnd: 800000,
  codeJob: true,
  assessment: false,
  skills: [],
  milestones: [
    { id: 'm1', title: 'Sửa lỗi trùng giờ', priceVnd: 800000, dueOn: '2026-11-20', revisionRounds: 2, criteria: [] },
  ],
  externalAccess: [],
}

const agreement = {
  versionNo: 1,
  sharedAt: '2026-10-19T03:00:00Z',
  versions: [{ versionNo: 1, sharedAt: '2026-10-19T03:00:00Z' }],
  quote: { priceVnd: 800000, timelineDays: 7 },
  crew: ['minhtq'],
  crewConfirmed: true,
  latestVersionNo: 1,
  comments: [],
  changeRequested: false,
  approvedAt: '2026-10-19T05:00:00Z',
  content,
  confirmed: {
    versionNo: 1,
    draftVersionNo: 1,
    crew: ['minhtq'],
    liveAccessIds: [],
    freezeCommitted: true,
    approvedAt: '2026-10-19T05:00:00Z',
    confirmedAt: '2026-10-19T06:00:00Z',
    content,
  },
}

const milestones = [
  { id: 'm1', number: 1, title: 'Sửa lỗi trùng giờ', dueOn: '2026-11-20', state: 'FUNDED', changeable: true, priceVnd: 800000, held: true, criteria: [] },
  { id: 'm2', number: 2, title: 'Nhắc lịch', dueOn: '2026-11-30', state: 'ACCEPTED', changeable: false, priceVnd: 800000, held: false, criteria: [] },
]

const open = (overrides: object = {}) => ({
  id: 'cr1',
  proposer: 'CUSTOMER',
  mine: true,
  description: 'Khách cần thêm thời gian kiểm tra',
  impact: null,
  items: [{ milestoneId: 'm1', number: 1, title: 'Sửa lỗi trùng giờ', oldDueOn: '2026-11-20', newDueOn: '2027-01-20' }],
  status: 'OPEN',
  customerApprovedAt: null,
  mentorConfirmedAt: null,
  agreementVersionNo: null,
  createdAt: '2026-10-20T03:00:00Z',
  closedAt: null,
  changes: { milestones: [], addedMilestones: [], criteria: [] },
  ...overrides,
})

const view = (overrides: object = {}) => ({
  jobId: 'j1',
  audience: 'CUSTOMER',
  version: 7,
  canPropose: true,
  open: null,
  history: [],
  milestones,
  scope: 'Sửa lỗi đặt bàn',
  ...overrides,
})

const backend = (routes: Record<string, (request: Request) => Response | Promise<Response>>) =>
  fakeBackend(signedInUser, {
    ...inEveryJob('CUSTOMER'),
    'GET /api/v1/jobs/j1': () => json(job),
    'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false }),
    'GET /api/v1/jobs/j1/agreement': () => json(agreement),
    'GET /api/v1/boards/j1/agreement-versions': () =>
      json({
        jobId: 'j1',
        versions: [
          { versionNo: 2, confirmedAt: '2026-10-25T03:00:00Z', scope: 'Sửa lỗi đặt bàn', milestones: [{ id: 'm1', title: 'Sửa lỗi trùng giờ', dueOn: '2027-01-20', criteria: [] }] },
          { versionNo: 1, confirmedAt: '2026-10-19T06:00:00Z', scope: 'Sửa lỗi đặt bàn', milestones: [{ id: 'm1', title: 'Sửa lỗi trùng giờ', dueOn: '2026-11-20', criteria: [] }] },
        ],
      }),
    ...routes,
  })

describe('JOB-6 full Change Requests (FR-36, Story 14.1)', () => {
  it('sends only what changes and shows each change before anyone agrees', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: Record<string, unknown>[] = []
    const planned = {
      id: 'm3',
      number: 3,
      title: 'Báo cáo',
      dueOn: '2026-12-20',
      state: 'PLANNED',
      changeable: true,
      priceVnd: 500000,
      held: false,
      criteria: [{ id: 'c3', statement: 'Có báo cáo tuần', checkSteps: '1. Mở báo cáo.' }],
    }
    const proposed = open({
      items: [],
      changes: {
        milestones: [{ milestoneId: 'm3', number: 3, oldTitle: 'Báo cáo', oldPriceVnd: 500000, priceVnd: 600000, remove: false }],
        addedMilestones: [],
        criteria: [{ kind: 'ADDED', criterionId: 'c9', milestoneNumber: 3, statement: 'Có biểu đồ', checkSteps: '1. Xem biểu đồ.' }],
      },
    })
    backend({
      'GET /api/v1/boards/j1/change-requests': () => json(view({ milestones: [...milestones, planned] })),
      'POST /api/v1/boards/j1/change-requests': async (request) => {
        sent.push(await request.json())
        return json(view({ canPropose: false, open: proposed, milestones: [...milestones, planned] }), 201)
      },
    })
    renderAt('/cong-viec/j1/thoa-thuan')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Đề nghị thay đổi' })).closest('section')!
    await user.click(await within(section).findByRole('button', { name: 'Đề nghị hạn mới' }))
    const more = within(section).getAllByText('Đổi tên, giá, tiêu chí hoặc bỏ giai đoạn')
    await user.click(more[more.length - 1])
    expect(within(section).getByText(/tăng giá cần thanh toán thêm/)).toBeInTheDocument()
    const price = within(section).getByLabelText('Giá giai đoạn 3 (đồng)')
    await user.clear(price)
    await user.type(price, '600000')
    const add = within(section).getAllByRole('button', { name: 'Thêm tiêu chí' })
    await user.click(add[add.length - 1])
    await user.type(within(section).getByLabelText('Tiêu chí mới của giai đoạn 3'), 'Có biểu đồ')
    const steps = within(section).getAllByLabelText('Cách kiểm tra')
    await user.type(steps[steps.length - 1], '1. Xem biểu đồ.')
    await user.type(within(section).getByLabelText(/Bạn muốn thay đổi gì/), 'Thêm biểu đồ vào báo cáo')
    await user.click(within(section).getByRole('button', { name: 'Gửi đề nghị' }))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toEqual({
      items: [],
      version: 7,
      milestones: [{ milestoneId: 'm3', priceVnd: 600000, remove: false }],
      addedCriteria: [{ milestoneId: 'm3', statement: 'Có biểu đồ', checkSteps: '1. Xem biểu đồ.' }],
      description: 'Thêm biểu đồ vào báo cáo',
    })
    expect(await within(section).findByText('Giai đoạn 3: giá từ 500.000 ₫ thành 600.000 ₫')).toBeInTheDocument()
    expect(within(section).getByText('Giai đoạn 3: thêm tiêu chí Có biểu đồ')).toBeInTheDocument()
  })
})

describe('JOB-6 top-ups (FR-36, Story 14.2)', () => {
  it('asks the Customer to pay the difference before a raised Held price takes effect', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    const waiting = open({
      status: 'AWAITING_TOP_UP',
      impact: 'Thêm 200.000 ₫',
      customerApprovedAt: '2026-10-21T03:00:00Z',
      mentorConfirmedAt: '2026-10-21T04:00:00Z',
      topUpVnd: 200000,
      items: [],
      changes: {
        milestones: [{ milestoneId: 'm1', number: 1, oldTitle: 'Sửa lỗi trùng giờ', oldPriceVnd: 800000, priceVnd: 1000000, remove: false }],
        addedMilestones: [],
        criteria: [],
      },
    })
    backend({
      'GET /api/v1/boards/j1/change-requests': () => json(view({ canPropose: false, open: waiting })),
      'POST /api/v1/jobs/j1/top-up-orders': async (request) => {
        sent.push(await request.json())
        return json({ id: 'o2', payUrl: 'https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=TU', expiresAt: '2026-10-21T04:15:00Z' }, 201)
      },
    })
    renderAt('/cong-viec/j1/thoa-thuan')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Đề nghị thay đổi' })).closest('section')!
    expect(await within(section).findByText('Bạn cần thanh toán thêm 200.000 ₫ THỬ NGHIỆM trước khi thay đổi có hiệu lực')).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: 'Duyệt hạn mới' })).toBeNull()
    await user.click(within(section).getByRole('button', { name: 'Thanh toán thêm' }))

    await waitFor(() => expect(redirectTo).toHaveBeenCalledWith('https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_TxnRef=TU'))
    expect(sent).toEqual([{ version: 7 }])
  })
})

describe('JOB-6 due-date Change Requests (FR-36, UX-DR57)', () => {
  it('lets the Customer propose a new due date for a Milestone that is not final', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    backend({
      'GET /api/v1/boards/j1/change-requests': () => json(view()),
      'POST /api/v1/boards/j1/change-requests': async (request) => {
        sent.push(await request.json())
        return json(view({ canPropose: false, open: open() }), 201)
      },
    })
    renderAt('/cong-viec/j1/thoa-thuan')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Đề nghị thay đổi' })).closest('section')!
    expect(await within(section).findByText(/^Phiên bản 2 · /)).toBeInTheDocument()
    await user.click(within(section).getByRole('button', { name: 'Đề nghị hạn mới' }))
    expect(within(section).getByText('Không đổi được')).toBeInTheDocument()
    fireEvent.change(within(section).getByLabelText('Hạn mới 1'), { target: { value: '2027-01-20' } })
    await user.type(within(section).getByLabelText(/Bạn muốn thay đổi gì/), 'Khách cần thêm thời gian kiểm tra')
    await user.click(within(section).getByRole('button', { name: 'Gửi đề nghị' }))
    await waitFor(() =>
      expect(sent).toEqual([
        {
          items: [{ milestoneId: 'm1', newDueOn: '2027-01-20' }],
          version: 7,
          description: 'Khách cần thêm thời gian kiểm tra',
        },
      ]),
    )
    expect(await within(section).findByText('Giai đoạn 1: 20/11/2026 → 20/01/2027')).toBeInTheDocument()
    expect(within(section).getByText('Đang chờ người hướng dẫn nêu ảnh hưởng về thời gian.')).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: 'Duyệt hạn mới' })).toBeNull()
  })

  it('lets the Customer approve once the impact is there, through the Confirmation dialog', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    backend({
      'GET /api/v1/boards/j1/change-requests': () =>
        json(view({ canPropose: false, open: open({ impact: 'Thêm hai tuần' }) })),
      'POST /api/v1/change-requests/cr1/approve': async (request) => {
        sent.push(await request.json())
        return json(
          view({ canPropose: false, open: open({ impact: 'Thêm hai tuần', customerApprovedAt: '2026-10-21T03:00:00Z' }) }),
        )
      },
    })
    renderAt('/cong-viec/j1/thoa-thuan')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Đề nghị thay đổi' })).closest('section')!
    expect(within(section).getByText('Ảnh hưởng: Thêm hai tuần')).toBeInTheDocument()
    await user.click(within(section).getByRole('button', { name: 'Duyệt hạn mới' }))
    const dialog = await screen.findByRole('dialog', { name: 'Đổi hạn các giai đoạn?' })
    expect(within(dialog).getByText(/Không hoàn tác được/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Duyệt hạn mới' }))
    await waitFor(() => expect(sent).toEqual([{ version: 7 }]))
    expect(
      await within(section).findByText('Khách hàng đã duyệt. Đang chờ người hướng dẫn xác nhận.'),
    ).toBeInTheDocument()
  })
})
