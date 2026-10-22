import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { Toaster } from '@/components/Toaster'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'
import { DisputeSection } from './DisputeSection'

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

function renderSection() {
  render(
    <QueryClientProvider client={createQueryClient()}>
      <Toaster>
        <DisputeSection jobId="j1" />
      </Toaster>
    </QueryClientProvider>,
  )
}

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

const progress = {
  boardId: 'j1',
  title: 'Sửa sổ đặt bàn',
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

const open = {
  id: 'd1',
  openedBy: 'CUSTOMER',
  mine: true,
  reason: 'Giai đoạn chưa đạt',
  concern: 'HELD_MILESTONE',
  milestoneId: 'm1',
  openedAt: '2026-10-20T03:00:00Z',
  statementDueAt: '2026-10-25T03:00:00Z',
  statements: [],
  canState: false,
}

describe('JOB-1 statements (FR-52, Story 15.1)', () => {
  it('lets the other side send its statement once', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    const mentorOpen = { ...open, mine: false, canState: true }
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/boards/j1/dispute': () => json({ jobId: 'j1', audience: 'MENTOR', canOpen: false, open: mentorOpen }),
      'POST /api/v1/boards/j1/dispute/statement': async (request) => {
        sent.push(await request.json())
        return json({
          jobId: 'j1',
          audience: 'MENTOR',
          canOpen: false,
          open: {
            ...mentorOpen,
            canState: false,
            statements: [{ side: 'MENTOR', mine: true, text: 'Khách đổi yêu cầu giữa chừng.', submittedAt: '2026-10-21T03:00:00Z' }],
          },
        })
      },
    })
    renderSection()

    await user.type(await screen.findByLabelText(/Trình bày của bạn/), 'Khách đổi yêu cầu giữa chừng.')
    await user.click(screen.getByRole('button', { name: 'Gửi trình bày' }))

    expect(await screen.findByText('Khách đổi yêu cầu giữa chừng.', { selector: 'p' })).toBeInTheDocument()
    expect(sent).toEqual([{ text: 'Khách đổi yêu cầu giữa chừng.' }])
    expect(screen.queryByRole('button', { name: 'Gửi trình bày' })).toBeNull()
  })
})

describe('JOB-1 Disputes (FR-52, UX-DR22, UX-DR56)', () => {
  it('lets the Customer open a Dispute through the dialog that says what freezes', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress),
      'GET /api/v1/boards/j1/dispute': () => json({ jobId: 'j1', audience: 'CUSTOMER', canOpen: true, open: null }),
      'POST /api/v1/boards/j1/disputes': async (request) => {
        sent.push(await request.json())
        return json({ jobId: 'j1', audience: 'CUSTOMER', canOpen: false, open }, 201)
      },
    })
    renderAt('/cong-viec/j1')

    await user.click(await screen.findByRole('button', { name: 'Mở tranh chấp' }))
    const dialog = await screen.findByRole('dialog', { name: 'Mở tranh chấp?' })
    expect(within(dialog).getByText(/Tạm dừng: tiền đang giữ/)).toBeInTheDocument()
    expect(within(dialog).getByText(/Vẫn tiếp tục: trò chuyện/)).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Lý do'), 'Giai đoạn chưa đạt')
    await user.click(within(dialog).getByRole('button', { name: 'Mở tranh chấp' }))
    await waitFor(() => expect(sent).toEqual([{ reason: 'Giai đoạn chưa đạt' }]))
    expect(await screen.findByText('Đang tranh chấp — tiền và mọi thời hạn tạm dừng')).toBeInTheDocument()
  })

  it('shows the Frozen banner to a Crew member, who is never offered the action', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/progress': () => json({ ...progress, audience: 'CREW' }),
      'GET /api/v1/boards/j1/dispute': () =>
        json({ jobId: 'j1', audience: 'CREW', canOpen: false, open: { ...open, mine: false } }),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText('Đang tranh chấp — tiền và mọi thời hạn tạm dừng')).toBeInTheDocument()
    expect(screen.getByText(/Khách hàng mở tranh chấp lúc/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mở tranh chấp' })).toBeNull()
  })

  it('says the System opened it when a lock left the Job unable to go on (FR-59)', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress),
      'GET /api/v1/boards/j1/dispute': () =>
        json({
          jobId: 'j1',
          audience: 'CUSTOMER',
          canOpen: false,
          open: { ...open, openedBy: 'SYSTEM', mine: false, reason: 'Một bên tham gia bị khóa tài khoản' },
        }),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText(/vibefix mở tranh chấp lúc .* vì một bên tham gia bị khóa tài khoản/)).toBeInTheDocument()
  })
})
