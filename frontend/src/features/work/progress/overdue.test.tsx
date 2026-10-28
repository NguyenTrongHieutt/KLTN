import { render, screen } from '@testing-library/react'
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

const overdueMilestone = {
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
  overdueSince: '2026-11-21T03:00:00Z',
}

const progress = (audience: string) => ({
  boardId: 'j1',
  title: 'Sửa sổ đặt bàn',
  state: 'ACTIVE',
  productName: 'Sổ đặt bàn Quán Mây',
  mentorName: 'Đỗ Mạnh Hùng',
  audience,
  milestones: [overdueMilestone],
  pages: [],
  crew: [],
  mine: [],
  assessment: false,
})

describe('Overdue Milestones (FR-48, UX-DR27)', () => {
  it('shows the Customer the badge and the options, which are links only', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress('CUSTOMER')),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText('Trễ hạn từ 21/11')).toBeInTheDocument()
    expect(screen.getByText(/Giai đoạn này đã quá hạn. Bạn có thể: chờ thêm,/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'đề nghị hạn mới' })).toHaveAttribute('href', '/cong-viec/j1/thoa-thuan#doi-han')
    expect(screen.getByRole('link', { name: 'đề nghị hủy' })).toHaveAttribute('href', '#huy-cong-viec')
    expect(screen.getByRole('link', { name: 'mở tranh chấp' })).toHaveAttribute('href', '#tranh-chap')
  })

  it('shows the Mentor the badge without the Customer options', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/progress': () => json(progress('MENTOR')),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText('Trễ hạn từ 21/11')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'đề nghị hạn mới' })).toBeNull()
  })
})
