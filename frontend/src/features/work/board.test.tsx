import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob, heldJobs } from '@/test/fakeBackend'

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

const story = (n: number, m: number, c: number, title: string) => ({
  id: `w${n}`,
  key: `SDB-${n}`,
  type: 'STORY',
  title,
  description: '1. Mở. 2. Đặt.',
  status: 'TODO',
  milestoneId: `m${m}`,
  criterion: { criterionId: `c${n}`, milestoneNumber: m, number: c },
  seeded: true,
  version: 1,
})

const board = (mentor: boolean) => ({
  boardId: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  code: 'SDB',
  mentor,
  milestones: [
    { milestoneId: 'm1', number: 1, title: 'Sửa lỗi trùng giờ' },
    { milestoneId: 'm2', number: 2, title: 'Email nhắc lịch' },
  ],
  crew: [],
  workload: [],
  items: [
    story(1, 1, 1, 'Hai khách không đặt trùng bàn'),
    story(2, 1, 2, 'Khách thấy giờ còn trống'),
    story(3, 2, 1, 'Khách nhận email nhắc trước một ngày'),
  ],
})

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

const assignment = (jobState: string) => ({
  jobId: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  jobState,
  productName: 'Sổ đặt bàn Quán Mây',
  mentorName: 'Đỗ Mạnh Hùng',
  status: 'ACTIVE',
  scope: 'Sửa lỗi trùng giờ',
  versionNo: 1,
  sharePercent: 40,
  signed: true,
  codeJob: true,
  commitEmailNoticeAcknowledged: true,
  milestones: [],
})

describe('JOB-2 "Bảng công việc" (FR-22, AD-33, UX-DR36)', () => {
  it('shows the Mentor the seeded Stories in "Cần làm" with their keys and criterion links', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const writeText = vi.fn(() => Promise.resolve())
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/work-items': () => json(board(true)),
    })
    renderAt('/cong-viec/j1/bang')

    expect(await screen.findByRole('link', { name: 'Bảng công việc' })).toHaveAttribute('aria-current', 'page')
    const todo = await screen.findByRole('region', { name: /Cần làm/ })
    expect(within(todo).getAllByRole('listitem')).toHaveLength(3)
    for (const status of [/Đang làm/, /Chờ duyệt/, /Xong/]) {
      expect(within(screen.getByRole('region', { name: status })).getByText('Chưa có việc nào.')).toBeInTheDocument()
    }
    expect(screen.getByText(/Mỗi tiêu chí nghiệm thu trong thỏa thuận là một Story/)).toBeInTheDocument()
    const card = within(todo).getAllByRole('listitem')[2]
    expect(within(card).getByText('SDB-3')).toBeInTheDocument()
    expect(within(card).getByText('Story')).toBeInTheDocument()
    expect(within(card).getByText('Khách nhận email nhắc trước một ngày')).toBeInTheDocument()
    expect(within(card).getByText('Chưa giao')).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Tiêu chí 2.1: xem trong thỏa thuận' })).toHaveAttribute(
      'href',
      '/cong-viec/j1/thoa-thuan',
    )

    await user.click(within(card).getByRole('button', { name: 'Sao chép mã SDB-3' }))
    expect(writeText).toHaveBeenCalledWith('SDB-3')
    expect((await screen.findAllByText('Đã sao chép SDB-3')).length).toBeGreaterThan(0)
  })

  it('lets an active Crew member open it from "Việc tôi tham gia" once the Job is Active, saying their role', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      ...heldJobs({ jobId: 'j1', title: 'Sửa lỗi đặt bàn và thêm nhắc lịch', role: 'CREW', crewKind: 'ACTIVE' }),
      'GET /api/v1/crew/assignments': () => json([assignment('ACTIVE')]),
      'GET /api/v1/team': () => json({ team: null }),
      'GET /api/v1/boards/j1/work-items': () => json(board(false)),
    })
    renderAt('/cong-viec')

    await user.click(await screen.findByRole('link', { name: 'Bảng công việc' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Sửa lỗi đặt bàn và thêm nhắc lịch' })).toBeInTheDocument()
    expect(await screen.findByText('Bạn là: Thành viên nhóm')).toBeInTheDocument()
    expect(await screen.findByText('Hai khách không đặt trùng bàn')).toBeInTheDocument()
    expect(screen.getAllByText('Tiêu chí 1.1')[0]).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /xem trong thỏa thuận/ })).toBeNull()
  })

  it('offers no Board before the Job is Active', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      ...heldJobs({ jobId: 'j1', title: 'Sửa lỗi đặt bàn và thêm nhắc lịch', role: 'CREW', crewKind: 'ACTIVE' }),
      'GET /api/v1/crew/assignments': () => json([assignment('AGREEMENT')]),
      'GET /api/v1/team': () => json({ team: null }),
    })
    renderAt('/cong-viec')

    expect(await screen.findByRole('link', { name: 'Trao đổi nhóm' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Bảng công việc' })).toBeNull()
  })

  it('is not found for a Customer', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, inEveryJob('CUSTOMER'))
    renderAt('/cong-viec/j1/bang')
    expect(await screen.findByRole('heading', { name: /Không tìm thấy/ })).toBeInTheDocument()
  })
})
