import { render, screen, waitFor, within } from '@testing-library/react'
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

const overview = {
  failedOperations: { count: 0 },
  stalledEvents: { count: 0 },
  activeAdministrators: 2,
  disputes: { count: 0 },
  lowFeedback: { count: 0 },
}

const post = {
  id: 'p1',
  kind: 'JOB_POST',
  title: 'Sửa lỗi đặt bàn',
  excerpt: 'Lỗi trùng giờ',
  authorName: 'Nguyễn Thị Lan',
  createdAt: '2026-10-20T03:00:00Z',
}

describe('ADM-6 moderation (FR-60)', () => {
  it('hides a Job Post with the reason its author reads', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    let hidden = false
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/moderation': (request) => {
        const query = new URL(request.url).searchParams
        if (query.get('kind') !== 'JOB_POST') {
          return json([])
        }
        const wantHidden = query.get('hidden') === 'true'
        return json(wantHidden === hidden ? [hidden ? { ...post, hiddenAt: '2026-10-21T03:00:00Z', hiddenReason: 'Lừa đảo' } : post] : [])
      },
      'POST /api/v1/admin/moderation/JOB_POST/p1/hide': async (request) => {
        sent.push(await request.json())
        hidden = true
        return json({ ...post, hiddenAt: '2026-10-21T03:00:00Z', hiddenReason: 'Lừa đảo' })
      },
    })
    renderAt('/quan-tri/kiem-duyet')

    await user.click(await screen.findByRole('tab', { name: 'Tin tuyển' }))
    expect(await screen.findByText('Sửa lỗi đặt bàn')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Ẩn' }))
    const dialog = await screen.findByRole('dialog', { name: 'Ẩn “Sửa lỗi đặt bàn”?' })
    await user.type(within(dialog).getByLabelText('Lý do (tác giả sẽ đọc)'), 'Lừa đảo')
    await user.click(within(dialog).getByRole('button', { name: 'Ẩn' }))
    await waitFor(() => expect(sent).toEqual([{ reason: 'Lừa đảo' }]))
    await waitFor(() => expect(screen.queryByText('Sửa lỗi đặt bàn')).toBeNull())
    await user.click(screen.getByRole('button', { name: 'Đã ẩn' }))
    expect(await screen.findByText('Đã ẩn: Lừa đảo')).toBeInTheDocument()
  })

  it('works an Abuse Report on a Crew Chat message through its one-message grant', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    const report = {
      id: 'r1',
      kind: 'CREW_CHAT_MESSAGE',
      targetId: 'm1',
      jobId: 'j1',
      label: 'Sửa lỗi đặt bàn',
      reason: 'Lời lẽ xúc phạm',
      reporterName: 'Trần Thảo Vy',
      authorName: 'Đỗ Mạnh Hùng',
      createdAt: '2026-10-20T03:00:00Z',
      status: 'OPEN',
      assigned: false,
      assignedToMe: false,
      hideable: false,
      itemReadable: false,
      outcome: null as string | null,
      decidedAt: null as string | null,
    }
    let current = report
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/moderation': () => json([]),
      'GET /api/v1/admin/abuse-reports': () =>
        json([{ id: 'r1', kind: 'CREW_CHAT_MESSAGE', label: 'Sửa lỗi đặt bàn', reason: 'Lời lẽ xúc phạm', reporterName: 'Trần Thảo Vy', createdAt: '2026-10-20T03:00:00Z', status: current.status, assigned: current.assigned, assignedToMe: current.assignedToMe }]),
      'GET /api/v1/admin/abuse-reports/r1': () => json(current),
      'POST /api/v1/admin/abuse-reports/r1/take': () => {
        current = { ...report, assigned: true, assignedToMe: true, itemReadable: true }
        return json(current)
      },
      'GET /api/v1/chat-messages/m1': () =>
        json({ id: 'm1', author: 'Đỗ Mạnh Hùng', mine: false, body: 'Nội dung tin nhắn bị báo cáo', maskedCredentials: 0, maskedPersonal: 0, sentAt: '2026-10-19T03:00:00Z' }),
      'POST /api/v1/admin/abuse-reports/r1/decision': async (request) => {
        sent.push(await request.json())
        current = { ...current, status: 'ACTION_TAKEN', outcome: 'Đã khóa tài khoản', decidedAt: '2026-10-21T03:00:00Z' }
        return json(current)
      },
    })
    renderAt('/quan-tri/kiem-duyet')

    await user.click(await screen.findByRole('tab', { name: 'Báo cáo vi phạm' }))
    await user.click(await screen.findByRole('button', { name: /Lời lẽ xúc phạm/ }))
    expect(screen.queryByText('Nội dung tin nhắn bị báo cáo')).toBeNull()
    await user.click(await screen.findByRole('button', { name: 'Nhận xử lý' }))
    expect(await screen.findByText('Nội dung tin nhắn bị báo cáo')).toBeInTheDocument()
    expect(screen.getByText('Mỗi lần xem nội dung này được ghi vào nhật ký kiểm toán.')).toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: 'Ẩn nội dung' })).toBeNull()
    await user.click(screen.getByRole('radio', { name: /Đã xử lý/ }))
    await user.type(screen.getByLabelText('Kết quả gửi người báo cáo'), 'Đã khóa tài khoản')
    await user.click(screen.getByRole('button', { name: 'Ghi quyết định' }))
    await waitFor(() => expect(sent).toEqual([{ action: 'ACTION_TAKEN', outcome: 'Đã khóa tài khoản' }]))
    expect(await screen.findByText('Đã khóa tài khoản')).toBeInTheDocument()
  })
})
