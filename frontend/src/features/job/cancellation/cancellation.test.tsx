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
  crew: [],
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

const crewMe = {
  jobId: 'j1',
  title: 'Sửa sổ đặt bàn',
  state: 'ACTIVE',
  productName: 'Sổ đặt bàn Quán Mây',
  mentorName: 'Đỗ Mạnh Hùng',
  former: false,
  canLeave: true,
  codeJob: true,
  partyMilestones: [],
}

/** A cancellation proposal of a Self-mentored Job: the Customer's, with two members asked and one agreed. */
const selfProposal = (overrides: object = {}) =>
  proposal({ scope: 'JOB', asked: 2, agreed: 1, mayAnswer: false, ...overrides })

/** The Customer of a Self-mentored Job (role `CUSTOMER_MENTOR`) reading its overview. */
function asCustomerMentor(handlers: Parameters<typeof fakeBackend>[1]) {
  return fakeBackend(signedInUser, {
    ...inEveryJob('CUSTOMER_MENTOR'),
    'GET /api/v1/jobs/j1': () => json({ ...customerJob, selfMentored: true }),
    'GET /api/v1/boards/j1/progress': () => json(progress()),
    ...handlers,
  })
}

/** A member of a Self-mentored Job reading its overview. */
function asMember(handlers: Parameters<typeof fakeBackend>[1]) {
  return fakeBackend(signedInUser, {
    ...inEveryJob('CREW'),
    'GET /api/v1/jobs/j1/crew/me': () => json(crewMe),
    'GET /api/v1/boards/j1/progress': () => json(progress({ audience: 'CREW' })),
    ...handlers,
  })
}

const memberView = (overrides: object = {}) =>
  cancellation({
    audience: 'CREW',
    selfMentored: true,
    canPropose: false,
    proposal: selfProposal({ mine: false, mayAnswer: true }),
    ...overrides,
  })

describe('JOB-1 cancellation of a Self-mentored Job (Story 25.7, R14, R56, R57)', () => {
  it('lets the Customer cancel at once, on their word alone, with no Held Milestone', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    asCustomerMentor({
      'GET /api/v1/boards/j1/cancellation': () => json(cancellation({ selfMentored: true, held: false })),
      'POST /api/v1/boards/j1/cancellation': async (request) => {
        sent.push(await request.json())
        return json(
          cancellation({
            selfMentored: true,
            held: false,
            state: 'CANCELLED',
            canPropose: false,
            proposal: selfProposal({ status: 'CONFIRMED', asked: undefined, agreed: undefined }),
          }),
        )
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    expect(within(section).getByText(/bạn hủy được ngay, không cần ai đồng ý/)).toBeInTheDocument()
    await user.click(within(section).getByRole('button', { name: 'Hủy công việc' }))
    await user.type(within(section).getByLabelText('Lý do'), 'Không còn cần nữa')
    await user.click(within(section).getByRole('button', { name: 'Hủy công việc' }))
    const dialog = await screen.findByRole('dialog', { name: 'Hủy công việc ngay?' })
    expect(within(dialog).getByText(/có hiệu lực ngay, chỉ cần lời của bạn/)).toBeInTheDocument()
    expect(within(dialog).getByText(/Các thành viên nhóm sẽ được báo/)).toBeInTheDocument()
    expect(within(dialog).queryByText(/bên kia|xác nhận/i)).toBeNull()
    await user.click(within(dialog).getByRole('button', { name: 'Hủy công việc' }))
    await waitFor(() => expect(sent).toEqual([{ scope: 'JOB', reason: 'Không còn cần nữa', version: 12 }]))
    // Cancelled: the section has nothing left to offer.
    await waitFor(() => expect(screen.queryByRole('heading', { level: 2, name: 'Hủy công việc' })).toBeNull())
    expect(screen.queryByRole('button', { name: 'Xác nhận hủy' })).toBeNull()
  })

  it('asks the Customer to confirm a proposal that waits for the members, with no date before it is sent', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    asCustomerMentor({
      'GET /api/v1/boards/j1/cancellation': () => json(cancellation({ selfMentored: true })),
      'POST /api/v1/boards/j1/cancellation': async (request) => {
        sent.push(await request.json())
        return json(
          cancellation({ selfMentored: true, canPropose: false, proposal: selfProposal({ asked: 2, agreed: 0 }) }),
        )
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    expect(within(section).getByText(/Mọi thành viên đang hoạt động phải đồng ý/)).toBeInTheDocument()
    await user.click(within(section).getByRole('button', { name: 'Đề nghị hủy' }))
    await user.type(within(section).getByLabelText('Lý do'), 'Không còn cần nữa')
    await user.click(within(section).getByRole('button', { name: 'Gửi đề nghị hủy' }))
    const dialog = await screen.findByRole('dialog', { name: 'Gửi đề nghị hủy cho các thành viên?' })
    expect(within(dialog).getByText(/Mọi thành viên đang hoạt động bây giờ phải đồng ý/)).toBeInTheDocument()
    expect(within(dialog).getByText(/hoàn đủ cho bạn/)).toBeInTheDocument()
    expect(within(dialog).getByText(/mở tranh chấp/)).toBeInTheDocument()
    // The answer date is the server's, fixed when the proposal is sent.
    expect(dialog.textContent).not.toMatch(/\d{1,2}\/\d{1,2}/)
    expect(sent).toEqual([])
    await user.click(within(dialog).getByRole('button', { name: 'Gửi đề nghị hủy' }))
    await waitFor(() => expect(sent).toEqual([{ scope: 'JOB', reason: 'Không còn cần nữa', version: 12 }]))
    expect(await within(section).findByText('Chờ 0/2 thành viên đồng ý trước 25/10')).toBeInTheDocument()
  })

  it('shows the Customer how many members agreed, lets them withdraw, and never offers to confirm', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let withdrawn = 0
    asCustomerMentor({
      'GET /api/v1/boards/j1/cancellation': () =>
        json(cancellation({ selfMentored: true, canPropose: false, proposal: selfProposal() })),
      'POST /api/v1/boards/j1/cancellation/withdraw': () => {
        withdrawn += 1
        return json(cancellation({ selfMentored: true, proposal: selfProposal({ status: 'WITHDRAWN' }) }))
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    expect(await within(section).findByText('Chờ 1/2 thành viên đồng ý trước 25/10')).toBeInTheDocument()
    expect(within(section).getByText('Không còn cần nữa')).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: 'Xác nhận hủy' })).toBeNull()
    await user.click(within(section).getByRole('button', { name: 'Rút đề nghị' }))
    await waitFor(() => expect(withdrawn).toBe(1))
  })

  it('tells the Customer when no member is left to ask, and offers only to withdraw', { timeout: 20_000 }, async () => {
    asCustomerMentor({
      'GET /api/v1/boards/j1/cancellation': () =>
        json(cancellation({ selfMentored: true, canPropose: false, proposal: selfProposal({ asked: 0, agreed: 0 }) })),
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    expect(await within(section).findByText('Không còn thành viên nào được hỏi. Hãy rút đề nghị rồi hủy lại.')).toBeInTheDocument()
    expect(within(section).queryByText(/Chờ 0\/0/)).toBeNull()
    expect(within(section).getByRole('button', { name: 'Rút đề nghị' })).toBeInTheDocument()
  })

  it('reads a refusal to the Customer, who may propose again or open a Dispute', { timeout: 20_000 }, async () => {
    asCustomerMentor({
      'GET /api/v1/boards/j1/cancellation': () =>
        json(cancellation({ selfMentored: true, proposal: selfProposal({ status: 'REFUSED' }) })),
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    expect(
      await within(section).findByText(/Một thành viên không đồng ý hủy\. Bạn có thể đề nghị lại hoặc mở tranh chấp\./),
    ).toBeInTheDocument()
    expect(within(section).getByRole('link', { name: 'Mở tranh chấp' })).toBeInTheDocument()
    expect(within(section).getByRole('button', { name: 'Đề nghị hủy' })).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: 'Rút đề nghị' })).toBeNull()
  })

  it('keeps the unanswered text for the Customer whose proposal passed its date', { timeout: 20_000 }, async () => {
    asCustomerMentor({
      'GET /api/v1/boards/j1/cancellation': () =>
        json(cancellation({ selfMentored: true, canPropose: false, proposal: selfProposal({ unanswered: true }) })),
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    expect(await within(section).findByText(/Bên kia chưa trả lời trong thời hạn/)).toBeInTheDocument()
    expect(within(section).getByRole('button', { name: 'Rút đề nghị' })).toBeInTheDocument()
  })

  it('lets a member agree, after a dialog that says the answer is final', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    asMember({
      'GET /api/v1/boards/j1/cancellation': () => json(memberView()),
      'POST /api/v1/boards/j1/cancellation/answer': async (request) => {
        sent.push(await request.json())
        return json(
          memberView({ proposal: selfProposal({ mine: false, mayAnswer: false, myAnswer: 'AGREED', asked: 3, agreed: 2 }) }),
        )
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    expect(await within(section).findByText('Khách hàng đề nghị hủy công việc. Hãy trả lời trước 25/10.')).toBeInTheDocument()
    expect(within(section).getByText('Không còn cần nữa')).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: 'Xác nhận hủy' })).toBeNull()
    expect(within(section).queryByRole('button', { name: 'Rút đề nghị' })).toBeNull()
    await user.click(within(section).getByRole('button', { name: 'Đồng ý hủy' }))
    const dialog = await screen.findByRole('dialog', { name: 'Đồng ý hủy công việc?' })
    expect(within(dialog).getByText(/hoàn đủ cho khách hàng/)).toBeInTheDocument()
    expect(within(dialog).getByText(/không đổi được câu trả lời/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Đồng ý hủy' }))
    await waitFor(() => expect(sent).toEqual([{ proposalId: 'cp1', agree: true }]))
    expect(await within(section).findByText('Bạn đã đồng ý hủy · 2/3 thành viên đã đồng ý')).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: 'Đồng ý hủy' })).toBeNull()
    expect(within(section).queryByRole('button', { name: 'Không đồng ý' })).toBeNull()
  })

  it('lets a member refuse, after a dialog that says the proposal ends', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    asMember({
      'GET /api/v1/boards/j1/cancellation': () => json(memberView()),
      'POST /api/v1/boards/j1/cancellation/answer': async (request) => {
        sent.push(await request.json())
        return json(
          memberView({
            proposal: selfProposal({ mine: false, mayAnswer: false, myAnswer: 'REFUSED', status: 'REFUSED' }),
          }),
        )
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    await user.click(await within(section).findByRole('button', { name: 'Không đồng ý' }))
    const dialog = await screen.findByRole('dialog', { name: 'Không đồng ý hủy công việc?' })
    expect(within(dialog).getByText(/Đề nghị hủy sẽ kết thúc/)).toBeInTheDocument()
    expect(within(dialog).getByText(/mở tranh chấp/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Không đồng ý' }))
    await waitFor(() => expect(sent).toEqual([{ proposalId: 'cp1', agree: false }]))
    expect(await within(section).findByText('Bạn đã không đồng ý hủy')).toBeInTheDocument()
    expect(within(section).getByRole('link', { name: 'Mở tranh chấp' })).toBeInTheDocument()
  })

  it('tells a member who joined later that they were not asked, with no action', { timeout: 20_000 }, async () => {
    asMember({
      'GET /api/v1/boards/j1/cancellation': () =>
        json(memberView({ proposal: selfProposal({ mine: false, mayAnswer: false }) })),
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    expect(
      await within(section).findByText('Bạn không được hỏi vì vào nhóm sau khi khách hàng gửi đề nghị.'),
    ).toBeInTheDocument()
    expect(within(section).getByText('Không còn cần nữa')).toBeInTheDocument()
    expect(within(section).queryByRole('button')).toBeNull()
  })

  it('shows a refusal of the answer inside the dialog', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    asMember({
      'GET /api/v1/boards/j1/cancellation': () => json(memberView()),
      'POST /api/v1/boards/j1/cancellation/answer': () =>
        json({ status: 403, code: 'FORBIDDEN', reason: 'NOT_ASKED' }, 403),
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    await user.click(await within(section).findByRole('button', { name: 'Đồng ý hủy' }))
    const dialog = await screen.findByRole('dialog', { name: 'Đồng ý hủy công việc?' })
    await user.click(within(dialog).getByRole('button', { name: 'Đồng ý hủy' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Đề nghị này không hỏi bạn.')
  })

  it('leaves a normal Job’s Crew member reading the proposal, with nothing to answer', { timeout: 20_000 }, async () => {
    asMember({
      'GET /api/v1/boards/j1/cancellation': () =>
        json(cancellation({ audience: 'CREW', canPropose: false, proposal: proposal({ mine: false }) })),
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hủy công việc' })).closest('section')!
    expect(await within(section).findByText('Trả lời trước 25/10.')).toBeInTheDocument()
    expect(within(section).getByText(/Khi cả hai bên đồng ý/)).toBeInTheDocument()
    expect(within(section).queryByRole('button')).toBeNull()
  })
})
