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

describe('Refused closures lead to a Dispute (FR-39)', () => {
  it('offers the Dispute after the closure proposal was declined', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/progress': () => json(progress),
      'GET /api/v1/boards/j1/closure': () =>
        json({ jobId: 'j1', state: 'ACTIVE', version: 12, audience: 'CUSTOMER', canPropose: true, proposal: null, steps: [], disputable: true }),
      'GET /api/v1/boards/j1/dispute': () => json({ jobId: 'j1', audience: 'CUSTOMER', canOpen: true, open: null, decided: null }),
    })
    renderAt('/cong-viec/j1')

    const section = (await screen.findByRole('heading', { level: 2, name: 'Kết thúc công việc' })).closest('section')!
    expect(within(section).getByText(/bị từ chối hoặc chưa được trả lời/)).toBeInTheDocument()
    expect(within(section).getByRole('link', { name: 'Mở tranh chấp' })).toHaveAttribute('href', '#tranh-chap')
    expect(await screen.findByRole('button', { name: 'Mở tranh chấp' })).toBeInTheDocument()
  })

  it('tells the other side of an unanswered cancellation that a Dispute may follow', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/progress': () => json({ ...progress, audience: 'MENTOR' }),
      'GET /api/v1/boards/j1/cancellation': () =>
        json({
          jobId: 'j1',
          audience: 'MENTOR',
          state: 'ACTIVE',
          version: 12,
          canPropose: false,
          canCancelAlone: false,
          held: true,
          proposal: {
            id: 'cp1',
            scope: 'REMAINING',
            reason: 'Không còn cần nữa',
            proposer: 'CUSTOMER',
            mine: false,
            status: 'OPEN',
            answerBy: '2026-10-25T03:00:00Z',
            unanswered: true,
            createdAt: '2026-10-20T03:00:00Z',
            decidedAt: null,
          },
        }),
    })
    renderAt('/cong-viec/j1')

    expect(await screen.findByText(/Đề nghị hủy chưa được trả lời trong thời hạn/)).toBeInTheDocument()
    const section = screen.getByText(/Đề nghị hủy chưa được trả lời trong thời hạn/).closest('p')!
    expect(within(section).getByRole('link', { name: 'Mở tranh chấp' })).toHaveAttribute('href', '#tranh-chap')
  })
})
