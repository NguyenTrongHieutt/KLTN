import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import type { Session } from '@/features/identity/api'
import { fakeBackend, json } from '@/test/fakeBackend'

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
  account: { id: '00000000-0000-7000-8000-00000000a001', role: 'ADMINISTRATOR', theme: 'LIGHT' },
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
  studentCards: { count: 0 },
  mentorApplications: { count: 0 },
  disputes: { count: 0 },
  lowFeedback: { count: 1 },
}

const vy = {
  id: 's1',
  role: 'STUDENT',
  name: 'Trần Thảo Vy',
  githubLogin: 'thaovy',
  email: 'vy@example.com',
  createdAt: '2026-10-01T03:00:00Z',
  verification: { method: 'SCHOOL_EMAIL', verifiedAt: '2026-10-02T03:00:00Z', validUntil: '2027-09-30', current: true },
  history: [],
}

const hung = {
  id: 'm1',
  role: 'MENTOR',
  name: 'Đỗ Mạnh Hùng',
  githubLogin: 'hungdo',
  createdAt: '2026-09-01T03:00:00Z',
  approval: 'APPROVED',
  history: [],
  feedback: { average: 1.7, count: 3, comments: ['Hay quát nạt'], enoughToAct: true },
}

const rows = [
  { id: 's1', role: 'STUDENT', name: 'Trần Thảo Vy', githubLogin: 'thaovy', locked: false, status: 'VERIFIED', createdAt: vy.createdAt },
  { id: 'm1', role: 'MENTOR', name: 'Đỗ Mạnh Hùng', githubLogin: 'hungdo', locked: false, status: 'APPROVED', createdAt: hung.createdAt },
]

describe('ADM-2 users (FR-59, FR-74, FR-78)', () => {
  it('searches, then locks a Student with a reason', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const searched: string[] = []
    const sent: unknown[] = []
    let current: object = vy
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/users': (request) => {
        searched.push(new URL(request.url).searchParams.get('q') ?? '')
        return json({ items: rows, total: 2, page: 0, size: 20 })
      },
      'GET /api/v1/admin/users/s1': () => json(current),
      'POST /api/v1/admin/users/s1/lock': async (request) => {
        sent.push(await request.json())
        current = {
          ...vy,
          lockedAt: '2026-10-21T03:00:00Z',
          lockReason: 'Gửi tin nhắn quấy rối',
          history: [{ action: 'LOCKED', reason: 'Gửi tin nhắn quấy rối', actedBy: 'Hà Phương', actedAt: '2026-10-21T03:00:00Z' }],
        }
        return json(current)
      },
    })
    renderAt('/quan-tri/nguoi-dung')

    await user.type(await screen.findByLabelText('Tìm theo tên, GitHub hoặc email'), 'thao vy')
    await user.click(screen.getByRole('button', { name: 'Tìm' }))
    await waitFor(() => expect(searched).toContain('thao vy'))
    await user.click(await screen.findByRole('link', { name: /Trần Thảo Vy/ }))
    expect(await screen.findByText(/Email trường, còn hiệu lực đến/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Khóa tài khoản' }))
    const dialog = await screen.findByRole('dialog', { name: 'Khóa tài khoản của Trần Thảo Vy?' })
    await user.type(within(dialog).getByLabelText('Lý do (người dùng sẽ đọc)'), 'Gửi tin nhắn quấy rối')
    await user.click(within(dialog).getByRole('button', { name: 'Khóa tài khoản' }))
    await waitFor(() => expect(sent).toEqual([{ reason: 'Gửi tin nhắn quấy rối' }]))
    expect(await screen.findByRole('button', { name: 'Mở khóa' })).toBeInTheDocument()
    expect(screen.getAllByText('Gửi tin nhắn quấy rối').length).toBeGreaterThan(0)
  })

  it('lists and opens a Google account, which has no role, without a role text', { timeout: 30_000 }, async () => {
    const lan = { id: 'g1', name: 'Nguyễn Thị Lan', email: 'lan@gmail.test', createdAt: '2026-10-25T03:00:00Z', history: [] }
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/users': () =>
        json({ items: [{ id: 'g1', name: 'Nguyễn Thị Lan', locked: false, createdAt: lan.createdAt }], total: 1, page: 0, size: 20 }),
      'GET /api/v1/admin/users/g1': () => json(lan),
    })
    renderAt('/quan-tri/nguoi-dung/g1')

    const role = (await screen.findAllByText('Vai trò')).find((element) => element.tagName === 'DT')
    expect(role?.nextElementSibling).toHaveTextContent(/^—$/)
    expect(screen.getAllByRole('link', { name: /Nguyễn Thị Lan/ }).length).toBeGreaterThan(0)
    expect(screen.queryByText(/screen\.admin\.users\.role/)).not.toBeInTheDocument()
    expect(screen.getByText('lan@gmail.test')).toBeInTheDocument()
  })

  it('shows a Mentor’s private feedback and revokes on its ground with a category', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    let current: object = hung
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/users': () => json({ items: rows, total: 2, page: 0, size: 20 }),
      'GET /api/v1/admin/users/m1': () => json(current),
      'POST /api/v1/admin/users/m1/revoke-approval': async (request) => {
        sent.push(await request.json())
        current = { ...hung, approval: 'REVOKED' }
        return json(current)
      },
    })
    renderAt('/quan-tri/nguoi-dung/m1')

    expect(await screen.findByText('Hay quát nạt')).toBeInTheDocument()
    expect(screen.getByText(/Chỉ quản trị viên xem được/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Thu hồi' }))
    const dialog = await screen.findByRole('dialog', { name: 'Thu hồi phê duyệt của Đỗ Mạnh Hùng?' })
    await user.click(within(dialog).getByRole('radio', { name: 'Dựa trên đánh giá kín' }))
    await user.selectOptions(within(dialog).getByLabelText(/Nhóm vi phạm/), 'GUIDANCE')
    await user.click(within(dialog).getByRole('button', { name: 'Thu hồi' }))
    const stepUp = await screen.findByRole('dialog')
    await user.type(within(stepUp).getByRole('textbox'), '123456')
    await user.click(within(stepUp).getByRole('button', { name: 'Thu hồi' }))
    await waitFor(() =>
      expect(sent).toEqual([{ ground: 'PRIVATE_FEEDBACK', category: 'GUIDANCE', stepUpCode: '123456' }]),
    )
    const account = screen.getByRole('region', { name: 'Đỗ Mạnh Hùng' })
    expect(await within(account).findByText('Đã thu hồi')).toBeInTheDocument()
    expect(within(account).queryByRole('button', { name: 'Thu hồi' })).toBeNull()
  })

  it('waits for the blocker, then confirms the account deletion through the step-up', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    let blockers = [{ module: 'job', reason: 'JOB_RUNNING', jobId: 'j1' }]
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/users': () => json({ items: rows, total: 2, page: 0, size: 20 }),
      'GET /api/v1/admin/users/s1': () =>
        json({ ...vy, deletionRequest: { requestedAt: '2026-10-20T03:00:00Z', completedAt: null, blockers } }),
      'POST /api/v1/admin/users/s1/erase': async (request) => {
        sent.push(await request.json())
        return json({ id: 's1', erased: true })
      },
    })
    renderAt('/quan-tri/nguoi-dung/s1')

    expect(await screen.findByRole('heading', { name: 'Yêu cầu xóa tài khoản' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Công việc đang chạy' })).toHaveAttribute('href', '/quan-tri/cong-viec/j1')
    expect(screen.getByRole('button', { name: 'Xác nhận xóa tài khoản' })).toBeDisabled()
    blockers = []
    cleanupAndRender()

    await user.click(await screen.findByRole('button', { name: 'Xác nhận xóa tài khoản' }))
    const stepUp = await screen.findByRole('dialog')
    await user.type(within(stepUp).getByRole('textbox'), '123456')
    await user.click(within(stepUp).getByRole('button', { name: 'Xác nhận xóa tài khoản' }))
    await waitFor(() => expect(sent).toEqual([{ stepUpCode: '123456' }]))
  })
})

function cleanupAndRender() {
  cleanup()
  renderAt('/quan-tri/nguoi-dung/s1')
}
