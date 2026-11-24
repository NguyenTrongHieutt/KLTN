import { render, screen, within } from '@testing-library/react'
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

const progress = (overrides: object = {}) => ({
  boardId: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  state: 'ACTIVE',
  productName: 'Sổ đặt bàn Quán Mây',
  mentorName: 'Đỗ Mạnh Hùng',
  audience: 'CUSTOMER',
  milestones: [
    { milestoneId: 'm1', number: 1, title: 'Sửa lỗi trùng giờ', state: 'FUNDED', priceVnd: 1500000, dueOn: '2026-11-20' },
    { milestoneId: 'm2', number: 2, title: 'Email nhắc lịch', state: 'PLANNED', priceVnd: 1500000, dueOn: '2026-12-10' },
    { milestoneId: 'm3', number: 3, title: 'Báo cáo', state: 'PLANNED', priceVnd: 500000 },
  ],
  crew: [],
  assessment: false,
  ...overrides,
})

const customerJob = {
  id: 'j1',
  productId: 'pr1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
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
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  version: 12,
  jobTypes: ['BUG_FIX'],
  selectedAt: '2026-10-18T03:00:00Z',
  codeJob: true,
  commitEmailNoticeAcknowledged: true,
  agreementConfirmed: true,
}

const crewMe = {
  jobId: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  state: 'ACTIVE',
  productName: 'Sổ đặt bàn Quán Mây',
  mentorName: 'Đỗ Mạnh Hùng',
  former: false,
  canLeave: true,
  codeJob: false,
  partyMilestones: [1],
}

/** An Assessment Job's one private read-only copy (R6, Story 23.11b). */
const COPY = 'https://github.com/vibefix-dev/job-1-intermediary'

/**
 * The Setup of an Active Assessment Job as the viewer reads it: its copy made, J7 applied, and the read invitations
 * out. Only the viewer's own row carries a GitHub link, so `own` is theirs, or nothing when they have no row.
 */
const assessmentSetup = (own?: Record<string, unknown>) => ({
  jobId: 'j1',
  jobState: 'ACTIVE',
  viewer: own?.role === 'CREW' ? 'CREW' : 'MENTOR',
  assessment: true,
  productId: 'pr1',
  status: 'DONE',
  steps: ['CHECK', 'CONFIRM', 'REPOSITORIES', 'COPY', 'INVITE'].map((key) => ({
    key,
    state: key === 'INVITE' ? 'CURRENT' : 'DONE',
  })),
  canRetry: false,
  people: own ? [own] : [],
})

describe('JOB-1 progress (FR-28, AD-33, UX-DR68)', () => {
  it("tells the Customer each Milestone's own state, with no progress figure", { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress()),
      'GET /api/v1/todos': () =>
        json({
          items: [
            {
              id: 't1',
              key: 'payment.fund_milestone',
              params: { n: '2', amount: '1.500.000 VND' },
              link: '/cong-viec/j1/thanh-toan',
              openedAt: '2026-10-20T03:00:00Z',
              deadlineNear: false,
              jobId: 'j1',
            },
          ],
          total: 1,
        }),
    })
    renderAt('/cong-viec/j1')

    const first = (await screen.findByRole('heading', { level: 3, name: 'Giai đoạn 1 · Sửa lỗi trùng giờ' })).closest('li')!
    expect(within(first).getByText('Đang làm')).toBeInTheDocument()
    expect(within(first).queryByRole('progressbar')).toBeNull()
    expect(within(first).getByText('THỬ NGHIỆM')).toBeInTheDocument()
    expect(within(first).getByText('Hạn 20/11')).toBeInTheDocument()
    const second = screen.getByRole('heading', { level: 3, name: 'Giai đoạn 2 · Email nhắc lịch' }).closest('li')!
    expect(within(second).getByText('Chưa thanh toán')).toBeInTheDocument()
    const third = screen.getByRole('heading', { level: 3, name: 'Giai đoạn 3 · Báo cáo' }).closest('li')!
    expect(within(third).getByText('Chưa bắt đầu')).toBeInTheDocument()
    // The Job's to-do is the strip's, on every tab: the page does not say it a second time.
    const next = await screen.findByRole('region', { name: 'Việc cần làm tiếp theo' })
    expect(within(next).getByText(/Thanh toán Giai đoạn 2/)).toBeInTheDocument()
    expect(screen.getAllByText(/Thanh toán Giai đoạn 2/)).toHaveLength(1)
    const aside = screen.getByRole('complementary', { name: 'Thông tin công việc' })
    expect(within(aside).getByText('Người hướng dẫn: Đỗ Mạnh Hùng')).toBeInTheDocument()
    expect(within(aside).getByText('Giai đoạn 1: 20/11')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Nhóm thực hiện' })).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Việc của tôi' })).toBeNull()
    expect(screen.queryByText(/tiêu chí đã xong/)).toBeNull()
    expect(screen.queryByText(/phần mềm độc hại/)).toBeNull()
    expect(screen.queryByRole('link', { name: /repo trung gian/ })).toBeNull()
  })

  /**
   * R6 (Story 23.11b): an Assessment Job's team reads its private read-only copy, never the Customer's Source
   * Repository, so the page links each viewer to their own row of the Setup -- the pending invitation here -- under
   * N-12.
   */
  it('links the Mentor of an Assessment Job to their own invitation with N-12 (FR-10)', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json({ ...mentorJob, jobTypes: ['ASSESSMENT'] }),
      'GET /api/v1/boards/j1/progress': () => json(progress({ audience: 'MENTOR', assessment: true })),
      'GET /api/v1/jobs/j1/setup': () =>
        json(
          assessmentSetup({
            role: 'MENTOR',
            name: 'Đỗ Mạnh Hùng',
            state: 'INVITED',
            expiresAt: '2026-11-25T03:00:00Z',
            githubUrl: `${COPY}/invitations`,
          }),
        ),
    })
    renderAt('/cong-viec/j1')

    const invitation = await screen.findByRole('link', { name: /Mở lời mời đọc repo trung gian trên GitHub/ })
    expect(invitation).toHaveAttribute('href', `${COPY}/invitations`)
    expect(invitation).toHaveAttribute('target', '_blank')
    expect(invitation).toHaveAttribute('rel', 'noopener noreferrer nofollow')
    expect(screen.getByText(/có thể chứa phần mềm độc hại/)).toBeInTheDocument()
    // The invitation is still out, so nothing yet opens the copy itself.
    expect(screen.queryByRole('link', { name: /Mở repo trung gian \(chỉ đọc\)/ })).toBeNull()
  })

  it('links a Crew member of an Assessment Job to the copy once they accepted', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/jobs/j1/crew/me': () => json(crewMe),
      'GET /api/v1/boards/j1/progress': () => json(progress({ audience: 'CREW', assessment: true })),
      'GET /api/v1/jobs/j1/setup': () =>
        json(assessmentSetup({ role: 'CREW', name: 'Lê Thảo Vy', state: 'ACCEPTED', githubUrl: COPY })),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('link', { name: /Mở repo trung gian \(chỉ đọc\) trên GitHub/ })).toHaveAttribute(
      'href',
      COPY,
    )
  })

  it('says the invitation is still going out, and shows no link yet', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json({ ...mentorJob, jobTypes: ['ASSESSMENT'] }),
      'GET /api/v1/boards/j1/progress': () => json(progress({ audience: 'MENTOR', assessment: true })),
      'GET /api/v1/jobs/j1/setup': () =>
        json(assessmentSetup({ role: 'MENTOR', name: 'Đỗ Mạnh Hùng', state: 'INVITING', githubUrl: COPY })),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText(/đang gửi lời mời đọc repo trung gian cho bạn/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /repo trung gian/ })).toBeNull()
  })

  it('shows no copy link to a Mentor with no row of their own', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json({ ...mentorJob, jobTypes: ['ASSESSMENT'] }),
      'GET /api/v1/boards/j1/progress': () => json(progress({ audience: 'MENTOR', assessment: true })),
      'GET /api/v1/jobs/j1/setup': () => json(assessmentSetup()),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText(/có thể chứa phần mềm độc hại/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /repo trung gian/ })).toBeNull()
    expect(screen.queryByText(/đang gửi lời mời/)).toBeNull()
  })

  it("adds the Crew with each member's state for the Mentor, and no work of theirs", { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/progress': () =>
        json(
          progress({
            audience: 'MENTOR',
            milestones: [progress().milestones[0]],
            crew: [
              { accountId: 's1', name: 'Trần Thảo Vy', state: 'ACTIVE' },
              { accountId: 's2', name: 'Lê Minh Tùng', state: 'ASSIGNED' },
              { accountId: 's4', name: 'Hoàng Kim Ngân', state: 'RELEASED' },
            ],
          }),
        ),
    })
    renderAt('/cong-viec/j1')

    // The card's own line says the due date, so the badge says only the state.
    expect(await screen.findByText('Đang làm')).toBeInTheDocument()
    expect(screen.getByText('Hạn 20/11')).toBeInTheDocument()
    const crew = screen.getByRole('heading', { level: 2, name: 'Nhóm thực hiện' }).closest('section')!
    expect(within(crew).getByText('Trần Thảo Vy')).toBeInTheDocument()
    expect(within(crew).queryByText('SDB-2')).toBeNull()
    expect(within(crew).queryByText('Chờ duyệt')).toBeNull()
    // UX-DR68, AD-41: each member's state on the Crew list; a former member is not in the people aside.
    for (const state of ['Đang tham gia', 'Đã phân công', 'Đã rời']) {
      expect(within(crew).getByText(state)).toBeInTheDocument()
    }
    // Story 20.4: a lock counts as leaving, so no member is "Tạm ngừng" and no reassignment banner shows.
    expect(within(crew).queryByText('Tạm ngừng')).toBeNull()
    expect(within(crew).queryByRole('status')).toBeNull()
    // The side column is one card since 2026-11-23: the people are one entry of its list.
    const people = screen.getByText('Người tham gia').closest('div')!
    expect(within(people).getByText('Nhóm thực hiện: Lê Minh Tùng')).toBeInTheDocument()
    expect(within(people).queryByText('Nhóm thực hiện: Hoàng Kim Ngân')).toBeNull()
  })

  it('gives an active Crew member their overview with N-12, and no work list', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/jobs/j1/crew/me': () => json({ ...crewMe, codeJob: true }),
      'GET /api/v1/boards/j1/progress': () => json(progress({ audience: 'CREW' })),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Sửa lỗi đặt bàn và thêm nhắc lịch' })).toBeInTheDocument()
    expect(await screen.findByText(/có thể chứa phần mềm độc hại/)).toBeInTheDocument()
    expect(screen.getByText('Đang làm')).toBeInTheDocument()
    expect(screen.getByText('Hạn 20/11')).toBeInTheDocument()
    expect(screen.getByText('Chờ khách thanh toán')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 2, name: 'Việc của tôi' })).toBeNull()
    // Story 24.2: the Board is retired, so JOB-1 offers the handovers and the Crew Chat, never a Board.
    expect(screen.queryByRole('link', { name: 'Bảng công việc' })).toBeNull()
    expect(screen.getByRole('link', { name: 'Bàn giao' })).toHaveAttribute('href', '/cong-viec/j1/ban-giao')
  })

  it('states the Revision Rounds left and what the Mentor must fix (FR-33, FR-34)', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/progress': () =>
        json(
          progress({
            audience: 'MENTOR',
            milestones: [{ ...progress().milestones[0], state: 'REVISION_REQUESTED', roundsLeft: 0, revisionCriteria: 1 }],
          }),
        ),
    })
    renderAt('/cong-viec/j1')
    expect(await screen.findByText('Cần sửa: 1 tiêu chí · còn 0 lượt')).toBeInTheDocument()
  })

  /** Story 24.2a: the Customer's own words for the same Milestone, with nothing counted after them. */
  it('reads the Customer a Milestone being revised, with no count after it', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () =>
        json(
          progress({
            milestones: [{ ...progress().milestones[0], state: 'REVISION_REQUESTED', roundsLeft: 1, revisionCriteria: 1 }],
          }),
        ),
    })
    renderAt('/cong-viec/j1')

    // A string matcher requires the whole text to equal it, so this alone proves nothing follows the label.
    expect(await screen.findByText('Đang sửa theo yêu cầu')).toBeInTheDocument()
  })

  it('tells the Customer how many Revision Rounds a submitted Milestone has left', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () =>
        json(progress({ milestones: [{ ...progress().milestones[0], state: 'SUBMITTED', roundsLeft: 2 }] })),
    })
    renderAt('/cong-viec/j1')
    expect(await screen.findByText('Đã bàn giao · chờ bạn nghiệm thu · còn 2 lượt sửa')).toBeInTheDocument()
  })

  it('labels a Milestone accepted automatically (FR-35)', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () =>
        json(progress({ milestones: [{ ...progress().milestones[0], state: 'ACCEPTED', autoAccepted: true }] })),
    })
    renderAt('/cong-viec/j1')
    expect(await screen.findByText('Đã tự nghiệm thu')).toBeInTheDocument()
  })

  it('is not found for a user the Job does not name', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, inEveryJob('CREW'))
    renderAt('/cong-viec/j1')
    expect(await screen.findByRole('heading', { name: /Không tìm thấy/ })).toBeInTheDocument()
  })
})
