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
  state: 'ACTIVE',
  title: 'Đánh giá sổ đặt bàn',
  jobTypes: ['ASSESSMENT'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-20T03:00:00Z',
  version: 12,
  agreementShared: true,
  agreementConfirmed: true,
  canCancelSelection: false,
}

const mentorJob = {
  id: 'j1',
  title: 'Đánh giá sổ đặt bàn',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  version: 12,
  jobTypes: ['ASSESSMENT'],
  selectedAt: '2026-10-18T03:00:00Z',
  codeJob: false,
  commitEmailNoticeAcknowledged: true,
  agreementConfirmed: true,
}

const progress = {
  boardId: 'j1',
  title: 'Đánh giá sổ đặt bàn',
  state: 'ACTIVE',
  productName: 'Sổ đặt bàn Quán Mây',
  mentorName: 'Đỗ Mạnh Hùng',
  audience: 'CUSTOMER',
  milestones: [],
  pages: [],
  crew: [],
  mine: [],
  assessment: true,
}

const followUp = {
  id: 'f1',
  recommendationNo: 2,
  title: 'Thêm nhắc lịch',
  jobTypes: ['NEW_FEATURE'],
  skillIds: ['s1'],
  scope: 'Nhắc khách trước giờ hẹn.',
  criteria: ['Khách nhận nhắc trước 1 giờ'],
  effortDays: 5,
  status: 'DRAFTING',
  draftJobId: null,
}

const followUps = (overrides: object = {}) => ({
  jobId: 'j1',
  audience: 'MENTOR',
  available: true,
  submitted: false,
  reportVersionNo: 2,
  recommendations: [
    { number: 1, text: 'Sửa lỗi hai khách đặt trùng bàn.' },
    { number: 2, text: 'Thêm nhắc lịch trước giờ hẹn.' },
  ],
  followUps: [],
  skillIds: ['s1'],
  ...overrides,
})

describe('JOB-1 follow-up Jobs (FR-11, UX-DR16)', () => {
  it('lets the Mentor draft a follow-up from a recommendation and send it', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/progress': () => json({ ...progress, audience: 'MENTOR' }),
      'GET /api/v1/boards/j1/follow-ups': () => json(followUps()),
      'POST /api/v1/boards/j1/follow-ups': async (request) => {
        sent.push(await request.json())
        return json(followUps({ followUps: [followUp] }), 201)
      },
      'POST /api/v1/boards/j1/follow-ups/submit': () =>
        json(followUps({ submitted: true, followUps: [{ ...followUp, status: 'SUBMITTED' }] })),
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Công việc tiếp theo' })).closest('section')!
    expect(within(section).getByText(/phiên bản 2/)).toBeInTheDocument()
    expect(within(section).getByRole('button', { name: 'Gửi khách hàng' })).toBeDisabled()
    await user.click(within(section).getByRole('button', { name: 'Thêm công việc tiếp theo' }))
    await user.selectOptions(within(section).getByLabelText('Đề xuất trong báo cáo'), '2')
    await user.type(within(section).getByLabelText('Tiêu đề'), 'Thêm nhắc lịch')
    await user.click(within(section).getByLabelText('Tính năng mới'))
    await user.type(within(section).getByLabelText('Phạm vi'), 'Nhắc khách trước giờ hẹn.')
    await user.type(within(section).getByLabelText(/Tiêu chí nghiệm thu đề xuất/), 'Khách nhận nhắc trước 1 giờ')
    await user.type(within(section).getByLabelText('Ước lượng công sức (ngày)'), '5')
    await user.click(within(section).getByRole('button', { name: 'Lưu' }))
    await waitFor(() =>
      expect(sent).toEqual([
        {
          recommendationNo: 2,
          title: 'Thêm nhắc lịch',
          jobTypes: ['NEW_FEATURE'],
          skillIds: ['s1'],
          scope: 'Nhắc khách trước giờ hẹn.',
          criteria: ['Khách nhận nhắc trước 1 giờ'],
          effortDays: 5,
        },
      ]),
    )
    expect(await within(section).findByText('Đề xuất 2 · Tính năng mới · Khoảng 5 ngày')).toBeInTheDocument()
    await user.click(within(section).getByRole('button', { name: 'Gửi khách hàng' }))
    expect(await within(section).findByText(/Đã gửi khách hàng/)).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: 'Thêm công việc tiếp theo' })).toBeNull()
  })

  it('lets the Customer open a follow-up as a Draft Job Post', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress),
      'GET /api/v1/boards/j1/follow-ups': () =>
        json(
          followUps({
            audience: 'CUSTOMER',
            submitted: true,
            followUps: [
              { ...followUp, status: 'SUBMITTED' },
              { ...followUp, id: 'f2', recommendationNo: 1, title: 'Sửa lỗi trùng bàn', status: 'SUBMITTED' },
            ],
          }),
        ),
      'POST /api/v1/follow-ups/f1/open': () => json({ draftJobId: 'd1' }),
      'POST /api/v1/follow-ups/f2/discard': () =>
        json(
          followUps({
            audience: 'CUSTOMER',
            submitted: true,
            followUps: [
              { ...followUp, status: 'SUBMITTED' },
              { ...followUp, id: 'f2', recommendationNo: 1, title: 'Sửa lỗi trùng bàn', status: 'DISCARDED' },
            ],
          }),
        ),
    })
    const router = renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Công việc tiếp theo' })).closest('section')!
    const skipped = within(section).getByText('Sửa lỗi trùng bàn').closest('li')!
    await user.click(within(skipped).getByRole('button', { name: 'Bỏ qua' }))
    expect(await within(section).findByText('Đã bỏ qua')).toBeInTheDocument()
    const opened = within(section).getByText('Thêm nhắc lịch').closest('li')!
    await user.click(within(opened).getByRole('button', { name: 'Mở để đăng tin' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/cong-viec/d1'))
  })
})
