import { render, screen, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'

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
    { milestoneId: 'm1', number: 1, title: 'Sửa lỗi trùng giờ', state: 'FUNDED', priceVnd: 1500000, dueOn: '2026-11-20', done: 1, total: 2, inReview: 0 },
    { milestoneId: 'm2', number: 2, title: 'Email nhắc lịch', state: 'PLANNED', priceVnd: 1500000, dueOn: '2026-12-10', done: 0, total: 1, inReview: 0 },
    { milestoneId: 'm3', number: 3, title: 'Báo cáo', state: 'PLANNED', priceVnd: 500000, done: 0, total: 1, inReview: 0 },
  ],
  pages: [{ id: 'p1', title: 'Hướng dẫn sử dụng' }],
  crew: [],
  mine: [],
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

describe('JOB-1 progress (FR-28, AD-33, UX-DR68)', () => {
  it('tells the Customer how far each Milestone has come, and nothing of the Board', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress()),
      'GET /api/v1/todos': () =>
        json({
          items: [
            {
              id: 't1',
              key: 'payment.fund_milestone',
              params: { n: '2', amount: '1.500.000 ₫' },
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
    expect(within(first).getByText('Đang làm · 1/2 tiêu chí đã xong')).toBeInTheDocument()
    expect(within(first).getByRole('progressbar')).toHaveAttribute('aria-valuenow', '1')
    expect(within(first).getByText('THỬ NGHIỆM')).toBeInTheDocument()
    expect(within(first).getByText('Hạn 20/11')).toBeInTheDocument()
    const second = screen.getByRole('heading', { level: 3, name: 'Giai đoạn 2 · Email nhắc lịch' }).closest('li')!
    expect(within(second).getByText('Chưa thanh toán')).toBeInTheDocument()
    const third = screen.getByRole('heading', { level: 3, name: 'Giai đoạn 3 · Báo cáo' }).closest('li')!
    expect(within(third).getByText('Chưa bắt đầu')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Hướng dẫn sử dụng' })).toHaveAttribute('href', '/cong-viec/j1/tai-lieu/p1')
    const next = await screen.findByRole('region', { name: 'Việc cần làm cho công việc này' })
    expect(within(next).getByText(/Thanh toán Giai đoạn 2/)).toBeInTheDocument()
    const aside = screen.getByRole('complementary', { name: 'Thông tin công việc' })
    expect(within(aside).getByText('Người hướng dẫn: Đỗ Mạnh Hùng')).toBeInTheDocument()
    expect(within(aside).getByText('Giai đoạn 1: 20/11')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Nhóm thực hiện' })).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Việc của tôi' })).toBeNull()
    expect(screen.queryByText(/phần mềm độc hại/)).toBeNull()
    expect(screen.queryByRole('link', { name: /Mở mã nguồn trên GitHub/ })).toBeNull()
  })

  it('links the Mentor of an Assessment Job to the Source Repository with N-12 (FR-10)', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () => json({ ...mentorJob, jobTypes: ['ASSESSMENT'] }),
      'GET /api/v1/boards/j1/progress': () =>
        json(progress({ audience: 'MENTOR', assessment: true, sourceRepositoryUrl: 'https://github.com/lan/so-dat-ban' })),
    })
    renderAt('/cong-viec/j1')

    const repository = await screen.findByRole('link', { name: /Mở mã nguồn trên GitHub/ })
    expect(repository).toHaveAttribute('href', 'https://github.com/lan/so-dat-ban')
    expect(repository).toHaveAttribute('target', '_blank')
    expect(repository).toHaveAttribute('rel', 'noopener noreferrer nofollow')
    expect(screen.getByText(/có thể chứa phần mềm độc hại/)).toBeInTheDocument()
  })

  it('adds the Crew and their open Work Items for the Mentor', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/progress': () =>
        json(
          progress({
            audience: 'MENTOR',
            milestones: [{ ...progress().milestones[0], inReview: 1 }],
            crew: [
              {
                accountId: 's1',
                name: 'Trần Thảo Vy',
                state: 'ACTIVE',
                openItems: [{ id: 'w2', key: 'SDB-2', title: 'Khách thấy giờ còn trống', status: 'IN_REVIEW' }],
              },
              { accountId: 's2', name: 'Lê Minh Tùng', state: 'ASSIGNED', openItems: [] },
              { accountId: 's3', name: 'Nguyễn Văn Minh', state: 'INACTIVE', openItems: [] },
              { accountId: 's4', name: 'Hoàng Kim Ngân', state: 'RELEASED', openItems: [] },
            ],
          }),
        ),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText('Đang làm · hạn 20/11 · 1 hạng mục chờ duyệt')).toBeInTheDocument()
    const crew = screen.getByRole('heading', { level: 2, name: 'Nhóm thực hiện' }).closest('section')!
    expect(within(crew).getByText('Trần Thảo Vy')).toBeInTheDocument()
    expect(within(crew).getByText('SDB-2')).toBeInTheDocument()
    expect(within(crew).getByText('Chờ duyệt')).toBeInTheDocument()
    // UX-DR68, AD-41: each member's state on the Crew list; a former member is not in the people aside.
    for (const state of ['Đang tham gia', 'Đã phân công', 'Tạm ngừng', 'Đã rời']) {
      expect(within(crew).getByText(state)).toBeInTheDocument()
    }
    const people = screen.getByRole('heading', { level: 3, name: 'Người tham gia' }).closest('section')!
    expect(within(people).getByText('Nhóm thực hiện: Nguyễn Văn Minh')).toBeInTheDocument()
    expect(within(people).queryByText('Nhóm thực hiện: Hoàng Kim Ngân')).toBeNull()
  })

  it('gives an active Crew member their overview with their Work Items and N-12', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/progress': () =>
        json(
          progress({
            audience: 'CREW',
            mine: [{ id: 'w1', key: 'SDB-1', title: 'Hai khách không đặt trùng bàn', status: 'IN_PROGRESS' }],
          }),
        ),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByRole('heading', { level: 1, name: 'Sửa lỗi đặt bàn và thêm nhắc lịch' })).toBeInTheDocument()
    expect(screen.getByText(/có thể chứa phần mềm độc hại/)).toBeInTheDocument()
    expect(screen.getByText('Đang làm · hạn 20/11')).toBeInTheDocument()
    expect(screen.getByText('Chờ khách thanh toán')).toBeInTheDocument()
    const mine = screen.getByRole('heading', { level: 2, name: 'Việc của tôi' }).closest('section')!
    expect(within(mine).getByText('SDB-1')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Bảng công việc' })).toHaveAttribute('href', '/cong-viec/j1/bang')
  })

  it('states the Revision Rounds left and what the Mentor must fix (FR-33, FR-34)', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
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

  it('tells the Customer how many Revision Rounds a submitted Milestone has left', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () =>
        json(progress({ milestones: [{ ...progress().milestones[0], state: 'SUBMITTED', roundsLeft: 2 }] })),
    })
    renderAt('/cong-viec/j1')
    expect(await screen.findByText('Đã bàn giao · chờ bạn nghiệm thu · còn 2 lượt sửa')).toBeInTheDocument()
  })

  it('labels a Milestone accepted automatically (FR-35)', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () =>
        json(progress({ milestones: [{ ...progress().milestones[0], state: 'ACCEPTED', autoAccepted: true }] })),
    })
    renderAt('/cong-viec/j1')
    expect(await screen.findByText('Đã tự nghiệm thu')).toBeInTheDocument()
  })

  it('is not found for a Student the Job does not name', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('STUDENT'))
    renderAt('/cong-viec/j1')
    expect(await screen.findByRole('heading', { name: /Không tìm thấy/ })).toBeInTheDocument()
  })
})
