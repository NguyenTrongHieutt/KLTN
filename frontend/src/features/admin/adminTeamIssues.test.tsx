import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import type { Session } from '@/features/identity/api'
import { administratorAccount, fakeBackend, json } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const minutes = (n: number) => new Date(Date.now() + n * 60_000).toISOString()

const administrator: Session = {
  status: 'SIGNED_IN',
  privacyNoticeVersion: '2.0',
  account: administratorAccount,
  administrator: {
    email: 'phuong@vibefix.vn',
    displayName: 'Hà Phương',
    idleExpiresAt: minutes(30),
    absoluteExpiresAt: minutes(480),
  },
}

afterEach(() => {
  vi.unstubAllGlobals()
})

const row = {
  id: 'ti1',
  jobId: 'j1',
  jobTitle: 'Sửa lỗi đặt bàn',
  phase: 'ESCALATED',
  escalatedAt: '2026-10-23T03:00:00Z',
  assigned: false,
  assignedToMe: false,
}

const detail = (overrides: object = {}) => ({
  id: 'ti1',
  jobId: 'j1',
  jobTitle: 'Sửa lỗi đặt bàn',
  raiserName: 'Trần Thảo Vy',
  mentorName: 'Đỗ Mạnh Hùng',
  reason: 'Điểm này chưa đúng với phần việc mình làm.',
  phase: 'ESCALATED',
  raisedAt: '2026-10-18T03:00:00Z',
  escalatedAt: '2026-10-23T03:00:00Z',
  answerKind: 'KEEP',
  assigned: false,
  assignedToMe: false,
  ...overrides,
})

describe('ADM-4 "Khiếu nại nội bộ" (FR-79, AD-40, UX-DR84)', () => {
  it('takes an escalated issue, reads the evidence, and decides it after the step-up', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const decided: unknown[] = []
    let taken = false
    const evidence = { rating: { stars: 2, comment: 'Còn chậm', createdAt: '2026-10-01T03:00:00Z' } }
    const done = detail({
      ...evidence,
      assigned: true,
      assignedToMe: true,
      phase: 'ENDED',
      ending: 'DECIDED',
      decision: { ratingRemoved: true, reasoning: 'Vy làm phần lớn việc', decidedAt: '2026-10-24T03:00:00Z' },
    })
    fakeBackend(administrator, {
      'GET /api/v1/admin/team-issues': () => json([row]),
      'GET /api/v1/admin/team-issues/ti1': () =>
        json(decided.length > 0 ? done : detail(taken ? { ...evidence, assigned: true, assignedToMe: true } : {})),
      'POST /api/v1/admin/team-issues/ti1/take': () => {
        taken = true
        return json(detail({ ...evidence, assigned: true, assignedToMe: true }))
      },
      'GET /api/v1/boards/j1/crew-chat/messages': () =>
        json({ items: [{ id: 'msg1', author: 'Trần Thảo Vy', mine: false, body: 'Mình làm tiếp phần đặt bàn nhé', maskedCredentials: 0, maskedPersonal: 0, sentAt: '2026-10-10T03:00:00Z' }], hasOlder: false }),
      'POST /api/v1/admin/team-issues/ti1/decision': async (request) => {
        decided.push(await request.json())
        return json(done)
      },
    })
    renderAt('/quan-tri/khieu-nai-noi-bo/ti1')

    expect(await screen.findByText('Điểm này chưa đúng với phần việc mình làm.')).toBeInTheDocument()
    expect(screen.getByText(/Trần Thảo Vy khiếu nại Đỗ Mạnh Hùng/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Khiếu nại nội bộ', current: 'page' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Nhận xử lý' }))

    expect(await screen.findByText('Mình làm tiếp phần đặt bàn nhé')).toBeInTheDocument()
    const tabs = within(screen.getByRole('tablist', { name: 'Hồ sơ bằng chứng nội bộ' })).getAllByRole('tab')
    expect(tabs.map((tab) => tab.textContent)).toEqual(['Trao đổi nhóm', 'Lịch sử công việc', 'Điểm'])
    await user.click(screen.getByRole('tab', { name: 'Điểm' }))
    expect(screen.getByText('2 sao')).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: /điểm phần trăm/ })).toBeNull()

    await user.click(screen.getByRole('radio', { name: 'Gỡ điểm' }))
    await user.type(screen.getByRole('textbox', { name: 'Lý do quyết định' }), 'Vy làm phần lớn việc')
    await user.click(screen.getByRole('button', { name: 'Ghi quyết định' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByRole('textbox'), '123456')
    await user.click(within(dialog).getByRole('button', { name: 'Ghi quyết định' }))

    expect(await screen.findByText(/Quyết định lúc/)).toBeInTheDocument()
    expect(screen.getByText('Gỡ điểm')).toBeInTheDocument()
    expect(decided).toEqual([{ removeRating: true, reasoning: 'Vy làm phần lớn việc', stepUpCode: '123456' }])
  })

  it('lists nothing yet', async () => {
    fakeBackend(administrator, { 'GET /api/v1/admin/team-issues': () => json([]) })
    renderAt('/quan-tri/khieu-nai-noi-bo')

    expect(await screen.findByText('Chưa có khiếu nại nội bộ nào được chuyển cho quản trị viên.')).toBeInTheDocument()
  })
})
