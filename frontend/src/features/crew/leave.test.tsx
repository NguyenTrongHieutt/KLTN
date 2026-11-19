import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, heldJobs, json, signedInUser } from '@/test/fakeBackend'

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

const TITLE = 'Sửa lỗi đặt bàn và thêm nhắc lịch'

const crewMe = (overrides: object = {}) => ({
  jobId: 'j1',
  title: TITLE,
  state: 'AGREEMENT',
  productName: 'Sổ đặt bàn Quán Mây',
  mentorName: 'Đỗ Mạnh Hùng',
  former: false,
  canLeave: true,
  codeJob: true,
  partyMilestones: [],
  ...overrides,
})

const role = (view: object) => ({ 'GET /api/v1/jobs/j1/role': () => json(view) })

const progress = (overrides: object = {}) => ({
  boardId: 'j1',
  title: TITLE,
  state: 'ACTIVE',
  productName: 'Sổ đặt bàn Quán Mây',
  mentorName: 'Đỗ Mạnh Hùng',
  audience: 'MENTOR',
  milestones: [],
  crew: [],
  assessment: false,
  ...overrides,
})

const mentorJob = {
  id: 'j1',
  title: TITLE,
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  version: 12,
  jobTypes: ['BUG_FIX'],
  selectedAt: '2026-10-18T03:00:00Z',
  codeJob: true,
  commitEmailNoticeAcknowledged: true,
  agreementConfirmed: true,
}

const customerJob = {
  id: 'j1',
  productId: 'pr1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  title: TITLE,
  jobTypes: ['BUG_FIX'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-18T03:00:00Z',
  version: 12,
  agreementShared: true,
  agreementConfirmed: true,
  canCancelSelection: false,
  mentor: { accountId: 'm', displayName: 'Đỗ Mạnh Hùng' },
}

const N28 = {
  repository: 'Quyền ghi vào kho làm việc của bạn bị thu hồi ngay, và bạn không vào lại công việc này được.',
  noRepository: 'Bạn không vào lại công việc này được.',
  split: 'Bạn vẫn là một bên trong phần chia tiền của Giai đoạn 1, 2, và vẫn làm các việc sau công việc',
  duties: 'Bạn vẫn làm các việc sau công việc như mọi thành viên: xóa dữ liệu của khách hàng, chấm sao, đánh giá kín.',
  customer: 'Khách hàng chỉ được báo tên tài khoản GitHub của bạn để thu hồi quyền.',
}

describe('JOB-1 for a Crew member (FR-77, UX-DR104)', () => {
  it('shows an active member the header and "Rời công việc" in Agreement', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...role({ role: 'CREW', crewKind: 'ACTIVE', left: false, noCrew: false }),
      'GET /api/v1/jobs/j1/crew/me': () => json(crewMe()),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('heading', { level: 1, name: TITLE })).toBeInTheDocument()
    expect(screen.getByText('Sổ đặt bàn Quán Mây')).toBeInTheDocument()
    expect(await screen.findByText('Bạn là: Thành viên nhóm')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Rời công việc' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Trao đổi nhóm' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Bảng công việc' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Bàn giao' })).toBeNull()
  })

  it('is not found for a pick not yet signed', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...role({ role: 'CREW', crewKind: 'ASSIGNED', left: false, noCrew: false }),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('heading', { level: 1, name: /Không tìm thấy/ })).toBeInTheDocument()
  })

  it('leaves with N-28 and the reason, which it marks as the Mentor\'s alone', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    let left = false
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/j1/role': () =>
        json({ role: 'CREW', crewKind: left ? 'FORMER' : 'ACTIVE', left, noCrew: false }),
      'GET /api/v1/jobs/j1/crew/me': () =>
        json(crewMe({ state: 'ACTIVE', partyMilestones: [1, 2], former: left, canLeave: !left })),
      'GET /api/v1/boards/j1/progress': () => json(progress({ audience: 'CREW' })),
      'GET /api/v1/boards/j1/data-deletion': () =>
        json({
          jobId: 'j1',
          ended: false,
          left: true,
          parties: [],
          mine: [{ item: 'NON_CODE_DATA' }, { item: 'PREVIEWS' }],
        }),
      // A read that outlasts the others: the button is gone by then, and the toast must not wait for it.
      'GET /api/v1/notifications/unread-count': async () => {
        if (left) {
          await new Promise((done) => setTimeout(done, 100))
        }
        return json({ count: 0 })
      },
      'POST /api/v1/jobs/j1/crew/leave': async (request) => {
        sent.push(await request.json())
        left = true
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/cong-viec/j1')

    await user.click(await screen.findByRole('button', { name: 'Rời công việc' }))
    const dialog = await screen.findByRole('dialog', { name: `Rời công việc ${TITLE}?` })
    expect(within(dialog).getByText(new RegExp(N28.repository))).toBeInTheDocument()
    expect(within(dialog).getByText(new RegExp(N28.split))).toBeInTheDocument()
    expect(within(dialog).queryByText(new RegExp(`^${N28.duties}`))).toBeNull()
    expect(within(dialog).getByText(new RegExp(N28.customer))).toBeInTheDocument()
    expect(within(dialog).getByText('Chỉ người hướng dẫn đọc được.')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Lý do (không bắt buộc)'), 'Mình đi thực tập')
    await user.click(within(dialog).getByRole('button', { name: 'Rời công việc' }))

    await waitFor(() => expect(sent).toEqual([{ reason: 'Mình đi thực tập' }]))
    expect(await screen.findByText('Bạn đã rời công việc này.')).toBeInTheDocument()
    expect(await screen.findByText('Bạn là: Thành viên nhóm · đã rời')).toBeInTheDocument()
    expect(
      await screen.findByText('Bạn không còn tham gia công việc này. Bạn chỉ xem được nội dung trong thời gian đã tham gia.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Rời công việc' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Bàn giao' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Bảng công việc' })).toBeNull()
    expect(await screen.findByRole('checkbox', { name: /Đã xóa các bản xem thử/ })).toBeEnabled()
  })

  it('a non-Code Job drops the repository; no Milestone leaves only the duties', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...role({ role: 'CREW', crewKind: 'ACTIVE', left: false, noCrew: false }),
      'GET /api/v1/jobs/j1/crew/me': () => json(crewMe({ codeJob: false, partyMilestones: [] })),
    })
    renderAt('/cong-viec/j1')

    await user.click(await screen.findByRole('button', { name: 'Rời công việc' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(`${N28.noRepository} ${N28.duties} ${N28.customer}`)).toBeInTheDocument()
  })

  it('shows the refusal of a Job already closing in the dialog', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...role({ role: 'CREW', crewKind: 'ACTIVE', left: false, noCrew: false }),
      'GET /api/v1/jobs/j1/crew/me': () => json(crewMe()),
      'POST /api/v1/jobs/j1/crew/leave': () =>
        json({ code: 'GUARD_FAILED', reason: 'LEAVE_CLOSED', status: 422 }, 422),
    })
    renderAt('/cong-viec/j1')

    await user.click(await screen.findByRole('button', { name: 'Rời công việc' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Rời công việc' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/quyền của bạn đã được thu hồi/)
  })

  it('keeps a former member\'s data-deletion duty before the Board, with no way to the handovers', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const confirmed: string[] = []
    fakeBackend(signedInUser, {
      ...role({ role: 'CREW', crewKind: 'FORMER', left: true, noCrew: false }),
      'GET /api/v1/jobs/j1/crew/me': () => json(crewMe({ former: true, canLeave: false })),
      'GET /api/v1/boards/j1/data-deletion': () =>
        json({
          jobId: 'j1',
          ended: false,
          left: true,
          parties: [{ party: 'CREW', name: 'Trần Thảo Vy', confirmed: false }],
          mine: [{ item: 'NON_CODE_DATA' }, { item: 'PREVIEWS' }],
        }),
      'POST /api/v1/boards/j1/data-deletion/PREVIEWS': () => {
        confirmed.push('PREVIEWS')
        return json({ jobId: 'j1', ended: false, left: true, parties: [], mine: [{ item: 'PREVIEWS', confirmedAt: '2026-11-02T03:00:00Z' }] })
      },
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText('Bạn là: Thành viên nhóm · đã rời')).toBeInTheDocument()
    await user.click(await screen.findByRole('checkbox', { name: /Đã xóa các bản xem thử/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Xác nhận đã xóa?' })
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))
    await waitFor(() => expect(confirmed).toEqual(['PREVIEWS']))
    expect(screen.queryByRole('button', { name: 'Rời công việc' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Bàn giao' })).toBeNull()
  })

  it('leaves a former member nothing but their duties on a cancelled Job', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...role({ role: 'CREW', crewKind: 'FORMER', left: false, noCrew: false }),
      'GET /api/v1/jobs/j1/crew/me': () => json(crewMe({ state: 'CANCELLED', former: true, canLeave: false })),
      'GET /api/v1/boards/j1/data-deletion': () =>
        json({
          jobId: 'j1',
          ended: true,
          left: false,
          parties: [],
          mine: [{ item: 'NON_CODE_DATA' }, { item: 'PREVIEWS' }],
        }),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('checkbox', { name: /Đã xóa các bản xem thử/ })).toBeEnabled()
    expect(screen.queryByRole('link', { name: 'Bảng công việc' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Bàn giao' })).toBeNull()
  })

  it('reads "· đã rời" after the Job ends for a row released LEFT, and not for one the end released', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...role({ role: 'CREW', crewKind: 'FORMER', left: false, noCrew: false }),
      'GET /api/v1/jobs/j1/crew/me': () => json(crewMe({ state: 'CLOSED', former: true, canLeave: false })),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText('Bạn là: Thành viên nhóm')).toBeInTheDocument()
    expect(screen.queryByText(/đã rời/)).toBeNull()
  })
})

describe('No Crew left (Q7, R8, UX-DR104)', () => {
  /** Story 24.2a: JOB-1 carries no Work Item of anyone's, so the reassignment line stays on JOB-2 alone. */
  it('shows the Mentor the banner on JOB-1 with "Tuyển thành viên", and no reassignment line', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...role({ role: 'MENTOR', left: false, noCrew: true }),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/progress': () => json(progress()),
    })
    renderAt('/cong-viec/j1')

    const banner = (await screen.findByText(/Nhóm thực hiện không còn ai\. Bạn có thể tiếp tục một mình/)).closest('[role="status"]')!
    expect(within(banner as HTMLElement).getByRole('link', { name: 'Tuyển thành viên' })).toHaveAttribute(
      'href',
      '/cong-viec/j1/tuyen-thanh-vien',
    )
    expect(screen.queryByText(/đã rời công việc ngày/)).toBeNull()
    expect(screen.queryByRole('button', { name: /Đóng/ })).toBeNull()
  })

  it('shows no banner while a member is on the Crew', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...role({ role: 'MENTOR', left: false, noCrew: false }),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/progress': () => json(progress()),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText('Bạn là: Người hướng dẫn')).toBeInTheDocument()
    expect(screen.queryByText(/Nhóm thực hiện không còn ai/)).toBeNull()
  })

  it('tells the Customer in plain words on JOB-1 that the Mentor carries on, and nothing else about the Crew', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...role({ role: 'CUSTOMER', left: false, noCrew: true }),
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress({ audience: 'CUSTOMER' })),
    })
    renderAt('/cong-viec/j1')

    expect(
      await screen.findByText(/Nhóm thực hiện của người hướng dẫn hiện không còn thành viên\. Người hướng dẫn Đỗ Mạnh Hùng vẫn tiếp tục công việc/),
    ).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Tuyển thành viên' })).toBeNull()
    expect(screen.queryByText(/Bạn có thể tiếp tục một mình/)).toBeNull()
  })

  it('shows the phase on JOB-H beside the state when the last member left before J6', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...role({ role: 'CUSTOMER', left: false, noCrew: false, phase: 'FINDING_CREW' }),
      'GET /api/v1/jobs/j1': () => json({ ...customerJob, state: 'AGREEMENT' }),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText('Đang tìm nhóm thực hiện')).toBeInTheDocument()
    expect(screen.queryByText(/Nhóm thực hiện của người hướng dẫn hiện không còn/)).toBeNull()
  })
})

describe('The Customer removes what a member who left had (FR-66, R15)', () => {
  const item = (overrides: object = {}) => ({
    accessId: 'a1',
    system: 'Supabase',
    purpose: 'Xem bảng đặt bàn',
    environment: 'NON_PRODUCTION',
    accessLevel: 'READ',
    identifiers: [],
    done: false,
    memberLogin: 'tung-dev',
    ...overrides,
  })

  it('lists each open item on JOB-1 in any state and ticks it with the member\'s username', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    let items = [item(), item({ accessId: 'a2', system: 'Vercel' })]
    fakeBackend(signedInUser, {
      ...role({ role: 'CUSTOMER', left: false, noCrew: false, phase: 'FINDING_CREW' }),
      'GET /api/v1/jobs/j1': () => json({ ...customerJob, state: 'AGREEMENT' }),
      'GET /api/v1/boards/j1/revocation-checklist': () => json({ jobId: 'j1', audience: 'CUSTOMER', items }),
      'POST /api/v1/boards/j1/revocation-checklist/a1': async (request) => {
        sent.push(await request.json())
        items = [item({ done: true, outcome: 'REMOVED', confirmedAt: '2026-11-02T03:00:00Z' }), items[1]]
        return json({ jobId: 'j1', audience: 'CUSTOMER', items })
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 3, name: 'Thu hồi quyền của người đã rời nhóm' })).closest('section')!
    expect(within(section).getByText(/Supabase của @tung-dev/)).toBeInTheDocument()
    expect(within(section).getByText(/Vercel của @tung-dev/)).toBeInTheDocument()
    const first = within(section).getByText(/Supabase của @tung-dev/).closest('li')!
    await user.click(within(first).getByRole('button', { name: 'Đã gỡ quyền' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))

    await waitFor(() => expect(sent).toEqual([{ outcome: 'REMOVED', memberLogin: 'tung-dev' }]))
    await waitFor(() => expect(screen.queryByText(/Supabase của @tung-dev/)).toBeNull())
    expect(screen.getByText(/Vercel của @tung-dev/)).toBeInTheDocument()
  })

  it('says so when the last open item is ticked, which empties the list', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let items = [item()]
    let ticked = false
    fakeBackend(signedInUser, {
      ...role({ role: 'CUSTOMER', left: false, noCrew: false, phase: 'FINDING_CREW' }),
      'GET /api/v1/jobs/j1': () => json({ ...customerJob, state: 'AGREEMENT' }),
      'GET /api/v1/boards/j1/revocation-checklist': () => json({ jobId: 'j1', audience: 'CUSTOMER', items }),
      // A read that outlasts the list it empties: the section is gone by then, and the toast must not wait for it.
      'GET /api/v1/notifications/unread-count': async () => {
        if (ticked) {
          await new Promise((done) => setTimeout(done, 100))
        }
        return json({ count: 0 })
      },
      'POST /api/v1/boards/j1/revocation-checklist/a1': () => {
        ticked = true
        items = [item({ done: true, outcome: 'REMOVED', confirmedAt: '2026-11-02T03:00:00Z' })]
        return json({ jobId: 'j1', audience: 'CUSTOMER', items })
      },
    })
    renderAt('/cong-viec/j1')

    await user.click(await screen.findByRole('button', { name: 'Đã gỡ quyền' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))

    expect(await screen.findByText('Đã ghi nhận.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Thu hồi quyền của người đã rời nhóm' })).toBeNull()
    expect(screen.queryByRole('dialog', { name: /Xác nhận đã thu hồi quyền/ })).toBeNull()
  })

  it('shows nothing with no open item', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...role({ role: 'CUSTOMER', left: false, noCrew: false }),
      'GET /api/v1/jobs/j1': () => json({ ...customerJob, state: 'AGREEMENT' }),
      'GET /api/v1/boards/j1/revocation-checklist': () =>
        json({ jobId: 'j1', audience: 'CUSTOMER', items: [item({ done: true }), item({ accessId: 'a2', memberLogin: undefined })] }),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText('Bạn là: Khách hàng')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Thu hồi quyền của người đã rời nhóm' })).toBeNull()
  })
})

describe('JOB-9 (FR-82)', () => {
  const recruitment = {
    crewVersion: 3,
    state: 'ACTIVE',
    available: true,
    recruiting: false,
    openSlots: 0,
    maxSlots: 4,
    cap: 5,
    signedMember: false,
    skills: [],
    crew: [
      {
        rowId: 'r1',
        name: 'Trần Thảo Vy',
        status: 'RELEASED',
        joinedAt: '2026-10-01T03:00:00Z',
        leftAt: '2026-10-20T03:00:00Z',
        leftNote: 'Mình đi thực tập',
      },
      { rowId: 'r2', name: 'Lê Minh Tùng', status: 'RELEASED', joinedAt: '2026-10-01T03:00:00Z', leftAt: '2026-10-21T03:00:00Z' },
    ],
  }

  it('reads the Mentor the reason a member gave, and has no remove action', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...role({ role: 'MENTOR', left: false, noCrew: true }),
      ...heldJobs(),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/mentor/jobs/j1/recruitment': () => json(recruitment),
      'GET /api/v1/mentor/jobs/j1/crew-applications': () => json([]),
    })
    renderAt('/cong-viec/j1/tuyen-thanh-vien')

    expect(await screen.findByText('Lý do (chỉ bạn đọc được): Mình đi thực tập')).toBeInTheDocument()
    expect(screen.getAllByText(/Lý do/)).toHaveLength(1)
    // Story 24.2a: JOB-9 no longer reads JOB-1's progress, which carried the reassignment line.
    expect(screen.queryByText(/đã rời công việc ngày/)).toBeNull()
    expect(screen.queryByRole('button', { name: /Xóa khỏi nhóm/ })).toBeNull()
  })
})
