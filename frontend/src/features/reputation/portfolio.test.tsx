import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

const customerJob = {
  id: 'j1',
  productId: 'pr1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'CLOSED',
  title: 'Đánh giá sổ đặt bàn',
  jobTypes: ['ASSESSMENT'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-20T03:00:00Z',
  version: 14,
  agreementShared: true,
  agreementConfirmed: true,
  canCancelSelection: false,
}

const mentorJob = {
  id: 'j1',
  title: 'Đánh giá sổ đặt bàn',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'CLOSED',
  version: 14,
  jobTypes: ['ASSESSMENT'],
  selectedAt: '2026-10-18T03:00:00Z',
  codeJob: false,
  commitEmailNoticeAcknowledged: true,
  agreementConfirmed: true,
}

const progress = {
  boardId: 'j1',
  title: 'Đánh giá sổ đặt bàn',
  state: 'CLOSED',
  productName: 'Sổ đặt bàn Quán Mây',
  mentorName: 'Đỗ Mạnh Hùng',
  audience: 'CUSTOMER',
  milestones: [],
  pages: [],
  crew: [],
  mine: [],
  assessment: true,
}

const mentorEntry = {
  role: 'MENTOR',
  title: 'Đánh giá sổ đặt bàn',
  jobTypes: ['ASSESSMENT'],
  skills: ['React'],
  milestones: 1,
  milestonesHandedOver: 1,
  reviewsWritten: 4,
  publishedAt: null,
}

const portfolioView = (overrides: object = {}) => ({
  jobId: 'j1',
  audience: 'MENTOR',
  exists: true,
  summaryStatus: 'DRAFT_DUE',
  summaryText: null,
  showProductName: false,
  productName: 'Sổ đặt bàn Quán Mây',
  decideBy: '2026-11-03T03:00:00Z',
  open: true,
  mine: mentorEntry,
  ...overrides,
})

describe('JOB-1 portfolio section (FR-50, UX-DR55)', () => {
  it('lets the Mentor draft the summary and shows their own entry', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/progress': () => json({ ...progress, audience: 'MENTOR' }),
      'GET /api/v1/boards/j1/portfolio': () => json(portfolioView()),
      'POST /api/v1/boards/j1/portfolio/summary': async (request) => {
        sent.push(await request.json())
        return json(
          portfolioView({
            summaryStatus: 'AWAITING_APPROVAL',
            summaryText: 'Nhóm đánh giá toàn bộ luồng.',
          }),
        )
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hồ sơ năng lực' })).closest('section')!
    expect(within(section).getByText(/1 giai đoạn đã bàn giao · 4 lượt duyệt việc/)).toBeInTheDocument()
    expect(within(section).getByText('Kỹ năng: React')).toBeInTheDocument()
    expect(within(section).getByText(/sẽ đăng sau khi bạn xác nhận đã xóa dữ liệu/)).toBeInTheDocument()
    await user.type(within(section).getByLabelText('Tóm tắt (tối đa 600 ký tự)'), 'Nhóm đánh giá toàn bộ luồng.')
    await user.click(within(section).getByRole('button', { name: 'Gửi khách hàng duyệt' }))
    await waitFor(() => expect(sent).toEqual([{ text: 'Nhóm đánh giá toàn bộ luồng.' }]))
    expect(await within(section).findByText('Đang chờ khách hàng duyệt trước 03/11.')).toBeInTheDocument()
    expect(within(section).getByText('Nhóm đánh giá toàn bộ luồng.')).toBeInTheDocument()
  })

  it('lets the Customer edit and approve the summary with the Product name', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress),
      'GET /api/v1/boards/j1/portfolio': () =>
        json(
          portfolioView({
            audience: 'CUSTOMER',
            summaryStatus: 'AWAITING_APPROVAL',
            summaryText: 'Bản nháp.',
            mine: null,
          }),
        ),
      'POST /api/v1/boards/j1/portfolio/summary/approve': async (request) => {
        sent.push(await request.json())
        return json(
          portfolioView({
            audience: 'CUSTOMER',
            summaryStatus: 'APPROVED',
            summaryText: 'Bản đã sửa.',
            open: false,
            mine: null,
          }),
        )
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hồ sơ năng lực' })).closest('section')!
    const text = within(section).getByLabelText('Tóm tắt')
    expect(text).toHaveValue('Bản nháp.')
    await user.clear(text)
    await user.type(text, 'Bản đã sửa.')
    await user.click(within(section).getByLabelText('Cho phép hiện tên sản phẩm "Sổ đặt bàn Quán Mây"'))
    await user.click(within(section).getByRole('button', { name: 'Duyệt' }))
    await waitFor(() => expect(sent).toEqual([{ text: 'Bản đã sửa.', showProductName: true }]))
    expect(await within(section).findByText('Khách hàng đã duyệt tóm tắt.')).toBeInTheDocument()
  })

  it('shows a Crew member their published entry and the approved summary', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/progress': () => json({ ...progress, audience: 'CREW' }),
      'GET /api/v1/boards/j1/portfolio': () =>
        json(
          portfolioView({
            audience: 'CREW',
            summaryStatus: 'APPROVED',
            summaryText: 'Bản đã duyệt.',
            showProductName: null,
            productName: null,
            open: false,
            mine: {
              ...mentorEntry,
              role: 'CREW',
              milestonesHandedOver: null,
              reviewsWritten: null,
              workItemsApproved: 3,
              publishedAt: '2026-10-21T03:00:00Z',
            },
          }),
        ),
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Hồ sơ năng lực' })).closest('section')!
    expect(within(section).getByText(/3 việc hoàn thành qua duyệt/)).toBeInTheDocument()
    expect(within(section).getByText('Đã đăng lên hồ sơ công khai của bạn.')).toBeInTheDocument()
    expect(within(section).getByText('Bản đã duyệt.')).toBeInTheDocument()
    expect(within(section).queryByRole('button')).toBeNull()
  })
})
