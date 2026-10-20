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
  updatedAt: '2026-10-18T03:00:00Z',
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
  assessment: false,
}

const steps = [
  { party: 'CUSTOMER', name: 'Lan Nguyễn', step: 'CONFIRM_CLOSURE', done: false },
  { party: 'MENTOR', name: 'Đỗ Mạnh Hùng', step: 'CONFIRM_CLOSURE', done: true },
]

const deletion = (overrides: object = {}) => ({
  jobId: 'j1',
  ended: false,
  parties: [
    { party: 'MENTOR', name: 'Đỗ Mạnh Hùng', confirmed: true },
    { party: 'CREW', name: 'Trần Thảo Vy', confirmed: false },
  ],
  ...overrides,
})

const closure = (overrides: object = {}) => ({
  jobId: 'j1',
  state: 'ACTIVE',
  version: 13,
  audience: 'CUSTOMER',
  canPropose: false,
  proposal: {
    id: 'cp1',
    kind: 'AUTOMATIC',
    status: 'OPEN',
    proposer: 'SYSTEM',
    autoConfirmAt: '2026-11-06T03:00:00Z',
    answerBy: '2026-11-06T03:00:00Z',
    customerConfirmed: false,
    mentorConfirmed: true,
    answerable: true,
  },
  steps,
  ...overrides,
})

describe('JOB-1 closure section (FR-37, FR-67, UX-DR55)', () => {
  it('lets the Customer confirm the System’s proposal and lists every party’s steps', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress),
      'GET /api/v1/boards/j1/closure': () => json(closure()),
      'GET /api/v1/boards/j1/data-deletion': () => json(deletion()),
      'POST /api/v1/boards/j1/closure/confirm': async (request) => {
        sent.push(await request.json())
        return json(closure({ state: 'CLOSED', proposal: { ...closure().proposal, status: 'CONFIRMED', answerable: false } }))
      },
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Kết thúc công việc' })).closest('section')!
    expect(within(section).getByText(/Hệ thống sẽ tự xác nhận kết thúc ngày 06\/11/)).toBeInTheDocument()
    expect(within(section).getByText('Khách hàng Lan Nguyễn: xác nhận kết thúc')).toBeInTheDocument()
    expect(within(section).getByText('Trần Thảo Vy: xác nhận đã xóa dữ liệu của khách hàng')).toBeInTheDocument()
    expect(within(section).getByText('Người hướng dẫn Đỗ Mạnh Hùng: xác nhận đã xóa dữ liệu của khách hàng')).toBeInTheDocument()
    expect(within(section).getAllByText('Chưa xong')).toHaveLength(2)
    await user.click(within(section).getByRole('button', { name: 'Xác nhận kết thúc' }))
    const dialog = await screen.findByRole('dialog', { name: 'Xác nhận kết thúc công việc?' })
    expect(within(dialog).getByText(/không hoàn tác được/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận kết thúc' }))
    await waitFor(() => expect(sent).toEqual([{ version: 13 }]))
    expect(await screen.findByRole('heading', { level: 2, name: 'Công việc đã hoàn thành' })).toBeInTheDocument()
  })

  it('lets the Mentor decline the Customer’s proposal', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/progress': () => json({ ...progress, audience: 'MENTOR' }),
      'GET /api/v1/boards/j1/closure': () =>
        json(
          closure({
            audience: 'MENTOR',
            proposal: {
              ...closure().proposal,
              kind: 'PROPOSED',
              proposer: 'CUSTOMER',
              autoConfirmAt: null,
              answerBy: '2026-10-25T03:00:00Z',
              customerConfirmed: true,
              mentorConfirmed: false,
            },
          }),
        ),
      'POST /api/v1/boards/j1/closure/decline': async (request) => {
        sent.push(await request.json())
        return json(closure({ audience: 'MENTOR', canPropose: true, proposal: null, steps: [] }))
      },
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText('Khách hàng đề nghị kết thúc công việc. Hãy trả lời trước 25/10.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Từ chối' }))
    await waitFor(() => expect(sent).toEqual([{ version: 13 }]))
    expect(await screen.findByRole('button', { name: 'Đề nghị kết thúc công việc' })).toBeInTheDocument()
  })

  it('shows a Crew member their own step and lets them tick the data-deletion Checklist', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/progress': () => json({ ...progress, audience: 'CREW' }),
      'GET /api/v1/boards/j1/closure': () =>
        json(
          closure({
            audience: 'CREW',
            proposal: { ...closure().proposal, answerable: false },
            steps,
          }),
        ),
      'GET /api/v1/boards/j1/data-deletion': () =>
        json(
          deletion({
            ended: true,
            parties: [{ party: 'CREW', name: 'Trần Thảo Vy', confirmed: false }],
            mine: [
              { item: 'NON_CODE_DATA', confirmedAt: '2026-10-20T03:00:00Z' },
              { item: 'PREVIEWS', confirmedAt: null },
            ],
          }),
        ),
      'POST /api/v1/boards/j1/data-deletion/PREVIEWS': () =>
        json(
          deletion({
            ended: true,
            parties: [{ party: 'CREW', name: 'Trần Thảo Vy', confirmed: true }],
            mine: [
              { item: 'NON_CODE_DATA', confirmedAt: '2026-10-20T03:00:00Z' },
              { item: 'PREVIEWS', confirmedAt: '2026-10-20T04:00:00Z' },
            ],
          }),
        ),
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Kết thúc công việc' })).closest('section')!
    expect(await within(section).findByText('Trần Thảo Vy: xác nhận đã xóa dữ liệu của khách hàng')).toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: 'Xác nhận kết thúc' })).toBeNull()
    const done = within(section).getByRole('checkbox', { name: /Đã xóa dữ liệu không phải mã nguồn/ })
    expect(done).toBeChecked()
    expect(done).toBeDisabled()
    await user.click(within(section).getByRole('checkbox', { name: 'Đã xóa các bản xem thử tôi đã triển khai' }))
    const dialog = await screen.findByRole('dialog', { name: 'Xác nhận đã xóa?' })
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))
    await waitFor(() =>
      expect(within(section).getByRole('checkbox', { name: /Đã xóa các bản xem thử/ })).toBeChecked(),
    )
  })

  it('says why a proposal is refused while a Milestone is held', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress),
      'GET /api/v1/boards/j1/closure': () => json(closure({ canPropose: true, proposal: null, steps: [] })),
      'POST /api/v1/boards/j1/closure': () =>
        json({ status: 422, code: 'GUARD_FAILED', reason: 'MILESTONE_HELD' }, 422),
    })
    renderAt('/cong-viec/j1')

    await user.click(await screen.findByRole('button', { name: 'Đề nghị kết thúc công việc' }))
    const dialog = await screen.findByRole('dialog', { name: 'Đề nghị kết thúc công việc?' })
    await user.click(within(dialog).getByRole('button', { name: 'Đề nghị kết thúc công việc' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Còn giai đoạn đang làm nên chưa kết thúc được.')
  })
})
