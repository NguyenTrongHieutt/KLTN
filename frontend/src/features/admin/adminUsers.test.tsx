import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
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
  lowFeedback: { count: 1 },
}

const vy = {
  id: 's1',
  name: 'Trần Thảo Vy',
  githubLogin: 'thaovy',
  email: 'vy@example.com',
  createdAt: '2026-10-01T03:00:00Z',
  verification: { method: 'SCHOOL_EMAIL', verifiedAt: '2026-10-02T03:00:00Z', validUntil: '2027-09-30', current: true },
  history: [],
  personLinks: [],
}

const hung = {
  id: 'm1',
  name: 'Đỗ Mạnh Hùng',
  githubLogin: 'hungdo',
  createdAt: '2026-09-01T03:00:00Z',
  // An Administrator's revocation from before Story 19.7 stays readable in the history.
  history: [
    {
      action: 'APPROVAL_REVOKED',
      reason: 'Thiếu tôn trọng với sinh viên',
      ground: 'PRIVATE_FEEDBACK',
      actedBy: 'Hà Phương',
      actedAt: '2026-10-10T03:00:00Z',
    },
  ],
  personLinks: [
    {
      accountId: 's1',
      name: 'Trần Thảo Vy',
      kinds: ['EMAIL', 'LINKEDIN'],
      locked: false,
      erased: false,
      createdAt: '2026-09-02T03:00:00Z',
    },
    { accountId: 'l2', name: 'Lê Bị Khóa', kinds: ['EMAIL'], locked: true, erased: false, createdAt: '2026-09-03T03:00:00Z' },
    { accountId: 'x9', kinds: ['EMAIL'], locked: false, erased: true, createdAt: '2026-09-04T03:00:00Z' },
  ],
  feedback: { average: 1.7, count: 3, comments: ['Hay quát nạt'] },
}

const rows = [
  { id: 's1', name: 'Trần Thảo Vy', githubLogin: 'thaovy', locked: false, status: 'VERIFIED', createdAt: vy.createdAt },
  { id: 'm1', name: 'Đỗ Mạnh Hùng', githubLogin: 'hungdo', locked: false, createdAt: hung.createdAt },
]

describe('ADM-2 users (FR-59, FR-78, AD-19)', () => {
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
    // Story 19.7: no verification is revoked here, and the status chips hold no approval.
    expect(screen.queryByRole('button', { name: 'Thu hồi xác minh' })).not.toBeInTheDocument()
    const statuses = screen.getByRole('group', { name: 'Trạng thái' })
    expect(within(statuses).getAllByRole('button').map((chip) => chip.textContent)).toEqual([
      'Đã khóa',
      'Đã xác minh',
      'Đánh giá kín thấp',
    ])
    // Story 20.3: no account role filters the list.
    expect(screen.queryByRole('group', { name: 'Vai trò' })).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Tài khoản có thể cùng một người' })).toHaveTextContent(
      'Không có tài khoản nào dùng chung dấu hiệu với tài khoản này.',
    )
    await user.click(screen.getByRole('button', { name: 'Khóa tài khoản' }))
    const dialog = await screen.findByRole('dialog', { name: 'Khóa tài khoản của Trần Thảo Vy?' })
    // Story 20.4: a lock counts as leaving every Crew.
    expect(within(dialog).getByText(/được tính là đã rời nhóm thực hiện của mọi công việc/)).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Lý do (người dùng sẽ đọc)'), 'Gửi tin nhắn quấy rối')
    await user.click(within(dialog).getByRole('button', { name: 'Khóa tài khoản' }))
    await waitFor(() => expect(sent).toEqual([{ reason: 'Gửi tin nhắn quấy rối' }]))
    expect(await screen.findByRole('button', { name: 'Mở khóa' })).toBeInTheDocument()
    expect(screen.getAllByText('Gửi tin nhắn quấy rối').length).toBeGreaterThan(0)
  })

  it('lists and opens an account with no role line, its verification and an empty private feedback', { timeout: 30_000 }, async () => {
    const lan = {
      id: 'g1',
      name: 'Nguyễn Thị Lan',
      email: 'lan@gmail.test',
      createdAt: '2026-10-25T03:00:00Z',
      history: [],
      personLinks: [],
    }
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/users': () =>
        json({ items: [{ id: 'g1', name: 'Nguyễn Thị Lan', locked: false, createdAt: lan.createdAt }], total: 1, page: 0, size: 20 }),
      'GET /api/v1/admin/users/g1': () => json(lan),
    })
    renderAt('/quan-tri/nguoi-dung/g1')

    expect(await screen.findByText('lan@gmail.test')).toBeInTheDocument()
    // Story 20.3: no account role; every user's verification and private feedback show, here empty.
    expect(screen.queryByText('Vai trò')).not.toBeInTheDocument()
    const verification = screen.getAllByText('Xác minh sinh viên').find((element) => element.tagName === 'DT')
    expect(verification?.nextElementSibling).toHaveTextContent('Chưa xác minh')
    expect(screen.getAllByRole('link', { name: /Nguyễn Thị Lan/ }).length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', { name: 'Đánh giá kín' })).toBeInTheDocument()
    expect(screen.getByText('Chưa có đánh giá kín nào về người này khi hướng dẫn.')).toBeInTheDocument()
  })

  it('shows a Mentor’s private feedback and who may be the same person, with nothing to revoke', { timeout: 30_000 }, async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/users': () => json({ items: rows, total: 2, page: 0, size: 20 }),
      'GET /api/v1/admin/users/m1': () => json(hung),
    })
    renderAt('/quan-tri/nguoi-dung/m1')

    expect(await screen.findByText('Hay quát nạt')).toBeInTheDocument()
    expect(screen.getByText(/Chỉ quản trị viên xem được/)).toBeInTheDocument()
    const links = screen.getByRole('region', { name: 'Tài khoản có thể cùng một người' })
    expect(within(links).getByRole('link', { name: 'Trần Thảo Vy' })).toHaveAttribute('href', '/quan-tri/nguoi-dung/s1')
    expect(within(links).getAllByText('Email')).toHaveLength(3)
    expect(within(links).getByText('LinkedIn')).toBeInTheDocument()
    expect(within(links).getByText('Tạo ngày 02/09/2026')).toBeInTheDocument()
    // A locked account still has its page, marked "Đã khóa".
    expect(within(links).getByRole('link', { name: 'Lê Bị Khóa' })).toHaveAttribute('href', '/quan-tri/nguoi-dung/l2')
    expect(within(links).getByText('Đã khóa')).toBeInTheDocument()
    // An erased account's signals outlive it; it has no name and no page.
    expect(within(links).getByText('Tài khoản đã xóa')).toBeInTheDocument()
    expect(within(links).getAllByRole('link')).toHaveLength(2)
    const account = screen.getByRole('region', { name: 'Đỗ Mạnh Hùng' })
    expect(within(account).queryByRole('button', { name: /Thu hồi/ })).toBeNull()
    expect(screen.queryByText('Phê duyệt người hướng dẫn')).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Lịch sử' })).toHaveTextContent('Thu hồi phê duyệt')
  })

  it('waits for the blocker, then confirms the account deletion through the step-up', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    let blockers = [
      { module: 'job', reason: 'JOB_RUNNING', jobId: 'j1', name: 'Sửa lỗi đặt bàn' },
      { module: 'dispute', reason: 'DISPUTE_OPEN', jobId: 'j1', name: 'Sửa lỗi đặt bàn' },
      { module: 'job', reason: 'MILESTONE_PENDING', jobId: 'j2', name: 'Trang đặt món' },
    ]
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
    expect(screen.getByRole('link', { name: 'Công việc đang chạy · Sửa lỗi đặt bàn' })).toHaveAttribute(
      'href',
      '/quan-tri/cong-viec/j1',
    )
    expect(screen.getByRole('link', { name: 'Tranh chấp đang mở · Sửa lỗi đặt bàn' })).toHaveAttribute(
      'href',
      '/quan-tri/cong-viec/j1',
    )
    // ADM-2 reads about another user, so its labels speak of them, never "bạn".
    expect(
      screen.getByRole('link', { name: 'Cột mốc người này đã tham gia chưa thanh toán xong · Trang đặt món' }),
    ).toHaveAttribute('href', '/quan-tri/cong-viec/j2')
    expect(screen.queryByText(/bạn đã tham gia/)).toBeNull()
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
