import { render, screen, waitFor, within } from '@testing-library/react'
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

const duc = {
  accountId: 'a1',
  displayName: 'Trịnh Văn Đức',
  githubLogin: 'duc-dev',
  yearsOfExperience: 2,
  status: 'PENDING',
  submittedAt: '2026-10-16T03:00:00Z',
  matches: 1,
}

const thao = { ...duc, accountId: 'a2', displayName: 'Nguyễn Phương Thảo', githubLogin: 'thao-dev', yearsOfExperience: 3, matches: 0 }

const detail = (overrides: object = {}) => ({
  accountId: 'a1',
  displayName: 'Trịnh Văn Đức',
  githubLogin: 'duc-dev',
  experience: 'Lập trình viên 2 năm ở một công ty phần mềm.',
  yearsOfExperience: 2,
  skills: [{ id: 'react', name: 'React', active: true }],
  linkedinUrl: 'https://www.linkedin.com/in/trinh-van-duc',
  document: { name: 'cv-duc.pdf', mediaType: 'application/pdf', size: 1258291, fileUrl: '/api/v1/files/f1' },
  privacyNoticeVersion: '2.0',
  submittedAt: '2026-10-16T03:00:00Z',
  status: 'PENDING',
  matches: [
    {
      accountId: 's1',
      role: 'STUDENT',
      locked: false,
      erased: false,
      verifiedUntil: '2099-09-30',
      kind: 'EMAIL',
      createdAt: '2026-03-03T02:00:00Z',
    },
  ],
  ...overrides,
})

const totals: Record<string, number> = { PENDING: 2, APPROVED: 14, REJECTED: 3 }

const applications = (request: Request) => {
  const url = new URL(request.url)
  const status = url.searchParams.get('status') ?? 'PENDING'
  if (url.searchParams.get('size') === '1') {
    return json({ items: [], page: 0, size: 1, total: totals[status] })
  }
  return json({ items: status === 'PENDING' ? [duc, thao] : [], page: 0, size: 20, total: status === 'PENDING' ? 2 : 0 })
}

const overview = () =>
  json({
    failedOperations: { count: 0 },
    stalledEvents: { count: 0 },
    activeAdministrators: 1,
    studentCards: { count: 0 },
    mentorApplications: { count: 2, oldestAt: '2026-10-15T03:00:00Z' },
  })

const refusal = (status: number, body: object) =>
  new Response(JSON.stringify({ status, ...body }), { status, headers: { 'Content-Type': 'application/problem+json' } })

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ADM-13 Mentor applications (FR-74, AD-19)', () => {
  it('lists pending applications oldest first with their counts and match badges', async () => {
    const seen = fakeBackend(administrator, {
      'GET /api/v1/admin/overview': overview,
      'GET /api/v1/admin/mentor-applications': applications,
    })
    renderAt('/quan-tri/nguoi-huong-dan')

    const list = await screen.findByRole('list', { name: 'Hồ sơ người hướng dẫn' })
    const items = within(list).getAllByRole('link')
    expect(items[0]).toHaveTextContent('Trịnh Văn Đức')
    expect(items[0]).toHaveTextContent('Gửi 16/10 · 2 năm kinh nghiệm')
    expect(items[0]).toHaveTextContent('1 tài khoản có thể cùng một người')
    expect(items[1]).not.toHaveTextContent('tài khoản có thể cùng một người')
    expect(await screen.findByRole('button', { name: 'Đã duyệt · 14' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Hồ sơ chờ duyệt · 2' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('Chọn một hồ sơ trong danh sách để xem.')).toBeInTheDocument()
    const request = new URL(seen.find((r) => r.url.includes('size=20'))!.url)
    expect(request.searchParams.get('status')).toBe('PENDING')
    const nav = screen.getAllByRole('navigation', { name: 'Quản trị' })[0]
    const links = within(nav).getAllByRole('link').map((link) => link.textContent)
    expect(links.findIndex((text) => text?.startsWith('Người hướng dẫn'))).toBeLessThan(
      links.findIndex((text) => text?.startsWith('Thẻ sinh viên')),
    )
    expect(within(nav).getByRole('link', { name: /Người hướng dẫn/ })).toHaveTextContent('2 đang chờ')
  })

  it('shows an application with the accounts that may be the same person, never the shared value', async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': overview,
      'GET /api/v1/admin/mentor-applications': applications,
      'GET /api/v1/admin/mentor-applications/a1': () => json(detail()),
    })
    renderAt('/quan-tri/nguoi-huong-dan/a1')

    expect(await screen.findByRole('heading', { name: 'Trịnh Văn Đức' })).toBeInTheDocument()
    expect(screen.getByText('Đang chờ duyệt')).toBeInTheDocument()
    expect(screen.getByText('@duc-dev · Gửi 16/10/2026')).toBeInTheDocument()
    expect(screen.getByText('Tài khoản có thể cùng một người: 1')).toBeInTheDocument()
    const matches = screen.getByRole('table', { name: 'Các tài khoản có thể cùng một người' })
    expect(within(matches).getByText('Sinh viên')).toBeInTheDocument()
    expect(within(matches).getByText('Đã xác minh · hết hạn 09/2099')).toBeInTheDocument()
    expect(within(matches).getByText('Trùng email đã xác minh')).toBeInTheDocument()
    expect(within(matches).getByText('03/03/2026')).toBeInTheDocument()
    expect(screen.getByText(/vibefix không hiển thị email hay liên kết bị trùng/)).toBeInTheDocument()
    expect(screen.getByText('cv-duc.pdf · 1,2 MB')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Mở tài liệu/ })).toHaveAttribute('href', '/api/v1/files/f1')
    expect(screen.getByText('(linkedin.com)')).toBeInTheDocument()
    expect(screen.getByText('· có tài khoản sinh viên đã xác minh trùng email')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /Ít nhất 2 năm kinh nghiệm/ })).not.toBeChecked()
    expect(screen.getByText('Bạn sẽ cần nhập mã xác thực 6 số trước khi gửi quyết định.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Trịnh Văn Đức/ })).toHaveAttribute('aria-current', 'true')
  })

  it('says so when no other account matches', async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': overview,
      'GET /api/v1/admin/mentor-applications': applications,
      'GET /api/v1/admin/mentor-applications/a2': () =>
        json(detail({ accountId: 'a2', displayName: 'Nguyễn Phương Thảo', matches: [], document: undefined })),
    })
    renderAt('/quan-tri/nguoi-huong-dan/a2')

    expect(await screen.findByText('Tài khoản có thể cùng một người: 0.')).toBeInTheDocument()
    expect(
      screen.getByText('Không có tài khoản nào khác trùng email đã xác minh hoặc liên kết LinkedIn.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.getByText('Không có tài liệu kèm theo.')).toBeInTheDocument()
  })

  it('approves after the code, telling a wrong code how many attempts are left', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    const bodies: object[] = []
    let attempts = 0
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': overview,
      'GET /api/v1/admin/mentor-applications': applications,
      'GET /api/v1/admin/mentor-applications/a2': () =>
        json(detail({ accountId: 'a2', displayName: 'Nguyễn Phương Thảo', matches: [] })),
      'POST /api/v1/admin/mentor-applications/a2/approve': async (request) => {
        bodies.push(await request.json())
        attempts += 1
        return attempts === 1
          ? refusal(422, { code: 'VALIDATION', reason: 'STEP_UP_FAILED', attemptsLeft: 4 })
          : json({ ...thao, status: 'APPROVED', decidedAt: '2026-10-19T03:00:00Z' })
      },
    })
    renderAt('/quan-tri/nguoi-huong-dan/a2')

    await user.click(await screen.findByRole('button', { name: 'Duyệt' }))
    const dialog = await screen.findByRole('dialog', { name: 'Duyệt hồ sơ của Nguyễn Phương Thảo?' })
    expect(within(dialog).getByText('Thảo nhận thông báo và dùng được mọi chức năng của người hướng dẫn')).toBeInTheDocument()
    expect(within(dialog).getByText('Nhóm của Thảo được tạo, đang có 0/10 sinh viên')).toBeInTheDocument()
    expect(within(dialog).getByText('Mã đổi sau mỗi 30 giây.')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Duyệt hồ sơ' }))
    expect(bodies).toHaveLength(0)
    await user.type(within(dialog).getByLabelText('Mã xác thực 6 số'), '000000')
    await user.click(within(dialog).getByRole('button', { name: 'Duyệt hồ sơ' }))
    expect(await within(dialog).findByText('Mã chưa đúng. Bạn còn 4 lần thử.')).toBeInTheDocument()

    await user.type(within(dialog).getByLabelText('Mã xác thực 6 số'), '123456')
    await user.click(within(dialog).getByRole('button', { name: 'Duyệt hồ sơ' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(bodies).toEqual([{ stepUpCode: '000000' }, { stepUpCode: '123456' }])
  })

  it('rejects with the reason the applicant reads', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let body: unknown
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': overview,
      'GET /api/v1/admin/mentor-applications': applications,
      'GET /api/v1/admin/mentor-applications/a1': () => json(detail()),
      'POST /api/v1/admin/mentor-applications/a1/reject': async (request) => {
        body = await request.json()
        return json({ ...duc, status: 'REJECTED', decidedAt: '2026-10-19T03:00:00Z' })
      },
    })
    renderAt('/quan-tri/nguoi-huong-dan/a1')

    await user.click(await screen.findByRole('button', { name: 'Từ chối' }))
    const dialog = await screen.findByRole('dialog', { name: 'Từ chối hồ sơ của Trịnh Văn Đức?' })
    expect(within(dialog).getByText('Người nộp nhận thông báo kèm lý do')).toBeInTheDocument()
    expect(within(dialog).getByText(/Họ có thể gửi lại hồ sơ từ ngày \d{2}\/\d{2}/)).toBeInTheDocument()
    expect(within(dialog).getByText('Tài liệu kèm theo tự xóa sau 30 ngày')).toBeInTheDocument()

    await user.type(within(dialog).getByLabelText('Mã xác thực 6 số'), '123456')
    await user.click(within(dialog).getByRole('button', { name: 'Từ chối hồ sơ' }))
    expect(await within(dialog).findByText('Hãy điền mục này.')).toBeInTheDocument()
    expect(body).toBeUndefined()

    await user.type(within(dialog).getByLabelText('Lý do (bắt buộc, người nộp sẽ thấy)'), 'Bạn đang là sinh viên.')
    await user.click(within(dialog).getByRole('button', { name: 'Từ chối hồ sơ' }))
    await waitFor(() => expect(body).toEqual({ reason: 'Bạn đang là sinh viên.', stepUpCode: '123456' }))
  })

  it('shows a decided application without the decision buttons', async () => {
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': overview,
      'GET /api/v1/admin/mentor-applications': applications,
      'GET /api/v1/admin/mentor-applications/a1': () =>
        json(detail({ status: 'REJECTED', decidedAt: '2026-10-18T03:00:00Z', rejectionReason: 'Bạn đang là sinh viên.' })),
    })
    renderAt('/quan-tri/nguoi-huong-dan/a1?status=REJECTED')

    expect(await screen.findByText('Đã từ chối 18/10/2026. Lý do: Bạn đang là sinh viên.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Duyệt' })).not.toBeInTheDocument()
    expect(screen.queryByText('Kiểm tra điều kiện')).not.toBeInTheDocument()
  })

  it('keeps planted payloads as text', async () => {
    const payload = '<img src=x onerror=alert(1)>'
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': overview,
      'GET /api/v1/admin/mentor-applications': applications,
      'GET /api/v1/admin/mentor-applications/a1': () =>
        json(detail({ displayName: payload, experience: payload, skills: [{ id: 'x', name: payload, active: true }] })),
    })
    renderAt('/quan-tri/nguoi-huong-dan/a1')

    expect(await screen.findByRole('heading', { name: payload })).toBeInTheDocument()
    expect(document.querySelector('img[src="x"]')).toBeNull()
  })
})
