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
  assessment: false,
}

const ratingsView = (overrides: object = {}) => ({
  jobId: 'j1',
  audience: 'CUSTOMER',
  openedAt: '2026-10-20T03:00:00Z',
  endsAt: '2026-11-03T03:00:00Z',
  open: true,
  toRate: [{ accountId: 'm1', name: 'Đỗ Mạnh Hùng', role: 'MENTOR' }],
  ratings: [],
  hiddenUntil: null,
  feedbackDue: null,
  feedbackGiven: null,
  ...overrides,
})

const mine = {
  kind: 'CUSTOMER_TO_MENTOR',
  raterRole: 'CUSTOMER',
  rateeId: 'm1',
  rateeName: 'Đỗ Mạnh Hùng',
  stars: 4,
  comment: 'Giải thích rõ ràng',
  createdAt: '2026-10-21T03:00:00Z',
  mine: true,
}

describe('JOB-1 Ratings (FR-49, FR-78, UX-DR27)', () => {
  it('lets the Customer rate the Mentor once and says when the Ratings show', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress),
      'GET /api/v1/boards/j1/ratings': () => json(ratingsView()),
      'POST /api/v1/boards/j1/ratings': async (request) => {
        sent.push(await request.json())
        return json(
          ratingsView({
            toRate: [],
            ratings: [mine],
            hiddenUntil: '2026-11-03T03:00:00Z',
          }),
          201,
        )
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Chấm sao' })).closest('section')!
    expect(within(section).getByText('Chấm sao và nhận xét cho từng người trước 03/11.')).toBeInTheDocument()
    const form = within(section).getByRole('group', {
      name: 'Người hướng dẫn Đỗ Mạnh Hùng',
    })
    expect(within(form).getByText(/Không thể sửa sau khi gửi/)).toBeInTheDocument()
    const send = within(form).getByRole('button', { name: 'Gửi đánh giá' })
    expect(send).toBeDisabled()
    await user.click(within(form).getByRole('radio', { name: '4 sao' }))
    expect(within(form).getByRole('radio', { name: '4 sao' })).toHaveAttribute('aria-checked', 'true')
    await user.type(within(form).getByLabelText('Nhận xét'), 'Giải thích rõ ràng')
    await user.click(send)
    const dialog = await screen.findByRole('dialog', {
      name: 'Gửi đánh giá cho Đỗ Mạnh Hùng?',
    })
    expect(within(dialog).getByText(/không thể sửa sau khi gửi/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Gửi đánh giá' }))
    await waitFor(() => expect(sent).toEqual([{ rateeId: 'm1', stars: 4, comment: 'Giải thích rõ ràng' }]))
    expect(await within(section).findByText('Điểm sẽ hiện khi cả hai bên đã chấm hoặc sau 03/11.')).toBeInTheDocument()
    expect(within(section).getByText('cho Đỗ Mạnh Hùng')).toBeInTheDocument()
    expect(
      within(section).queryByRole('group', {
        name: 'Người hướng dẫn Đỗ Mạnh Hùng',
      }),
    ).not.toBeInTheDocument()
  })

  it('shows the Customer both Ratings once published', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress),
      'GET /api/v1/boards/j1/ratings': () =>
        json(
          ratingsView({
            toRate: [],
            ratings: [
              mine,
              {
                ...mine,
                kind: 'MENTOR_TO_CUSTOMER',
                raterRole: 'MENTOR',
                rateeId: 'c1',
                rateeName: 'Lan',
                stars: 5,
                comment: 'Khách hàng phản hồi nhanh',
                mine: false,
              },
            ],
          }),
        ),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText('Khách hàng phản hồi nhanh')).toBeInTheDocument()
    expect(screen.getByText('Người hướng dẫn chấm')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: '5 sao' })).toBeInTheDocument()
    expect(screen.queryByText(/Điểm sẽ hiện/)).not.toBeInTheDocument()
  })

  it('lets a Crew member give private feedback that only Administrators read', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    let given = false
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/progress': () => json({ ...progress, audience: 'CREW' }),
      'GET /api/v1/boards/j1/ratings': () =>
        json(
          ratingsView({
            audience: 'CREW',
            toRate: [],
            hiddenUntil: '2026-11-03T03:00:00Z',
            feedbackDue: !given,
            feedbackGiven: given,
          }),
        ),
      'POST /api/v1/boards/j1/private-feedback': async (request) => {
        sent.push(await request.json())
        given = true
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Chấm sao' })).closest('section')!
    expect(within(section).getByText('Điểm sẽ hiện sau 03/11.')).toBeInTheDocument()
    const form = within(section).getByRole('group', {
      name: 'Đánh giá kín người hướng dẫn',
    })
    expect(within(form).getByText(/Chỉ quản trị viên đọc đánh giá này/)).toBeInTheDocument()
    await user.click(within(form).getByRole('radio', { name: '2 sao' }))
    await user.click(within(form).getByRole('button', { name: 'Gửi đánh giá kín' }))
    const dialog = await screen.findByRole('dialog', {
      name: 'Gửi đánh giá kín?',
    })
    await user.click(within(dialog).getByRole('button', { name: 'Gửi đánh giá kín' }))
    await waitFor(() => expect(sent).toEqual([{ stars: 2 }]))
    expect(await within(section).findByText('Bạn đã gửi đánh giá kín.')).toBeInTheDocument()
  })
})
