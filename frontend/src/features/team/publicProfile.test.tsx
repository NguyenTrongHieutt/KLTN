import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { anonymous, fakeBackend, json, signedIn } from '@/test/fakeBackend'
import { PAYLOADS } from '@/test/payloads'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const publicStudent = (overrides: object = {}) => ({
  githubLogin: 'minhtq',
  verified: true,
  memberSince: '2026-07-01T03:00:00Z',
  skillGroups: [
    { category: 'Giao diện web', skills: ['React', 'TypeScript'] },
    { category: 'Dữ liệu', skills: ['Supabase'] },
  ],
  team: { mentorDisplayName: 'Đỗ Mạnh Hùng', mentorLogin: 'hungdm', size: 6 },
  ...overrides,
})

const publicHung = (overrides: object = {}) => ({
  accountId: 'm1',
  githubLogin: 'hungdm',
  displayName: 'Đỗ Mạnh Hùng',
  experience: 'Kỹ sư phần mềm.',
  yearsOfExperience: 8,
  memberSince: '2026-06-01T03:00:00Z',
  skillGroups: [{ category: 'Giao diện web', skills: ['React'] }],
  available: true,
  teamSize: 6,
  sizeLimit: 10,
  listedMembers: [],
  ...overrides,
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('PUB-4, a Student\'s public profile (FR-51)', () => {
  it('shows the GitHub username for a hidden name, the Skills by Category, the Team, and no school', async () => {
    fakeBackend(anonymous, {
      'GET /api/v1/students/minhtq': () => json(publicStudent()),
      'GET /api/v1/students/minhtq/reputation': () => json({ count: 0, portfolio: [] }),
    })
    renderAt('/sv/minhtq')

    expect(await screen.findByRole('heading', { level: 1, name: '@minhtq' })).toBeInTheDocument()
    expect(screen.getByText('Đã xác minh sinh viên')).toBeInTheDocument()
    expect(screen.getByText('@minhtq trên GitHub')).toBeInTheDocument()
    expect(screen.getByText('Giao diện web:')).toBeInTheDocument()
    expect(screen.getByText('TypeScript')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Nhóm của Đỗ Mạnh Hùng' })).toHaveAttribute('href', '/mentor/hungdm')
    expect(await screen.findByText('Chưa có lượt chấm sao')).toBeInTheDocument()
    expect(screen.getByText('Chưa có công việc nào hoàn thành trên vibefix.')).toBeInTheDocument()
    expect(screen.getByText('Hồ sơ công khai không hiển thị email, số điện thoại hay số dư của sinh viên.')).toBeInTheDocument()
    expect(screen.queryByText(/Bách khoa/)).toBeNull()
  })

  it('shows a shown name with the school, the declared major, and the Mentors\' Ratings', async () => {
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/students/minhtq': () =>
        json(publicStudent({ name: 'Trần Quang Minh', school: 'hust.edu.vn', major: 'Khoa học máy tính', team: undefined })),
      'GET /api/v1/students/minhtq/reputation': () => json({ average: 4.5, count: 2, portfolio: [] }),
    })
    renderAt('/sv/minhtq')

    expect(await screen.findByRole('heading', { level: 1, name: 'Trần Quang Minh' })).toBeInTheDocument()
    expect(screen.getByText('@minhtq trên GitHub · hust.edu.vn · Khoa học máy tính')).toBeInTheDocument()
    expect(screen.getByText('Chưa ở trong nhóm nào.')).toBeInTheDocument()
    expect(await screen.findByText('4,5 sao')).toBeInTheDocument()
    expect(screen.getByText('(2 lượt từ người hướng dẫn)')).toBeInTheDocument()
  })

  it('shows the not-found screen for a Student who has no public profile', async () => {
    fakeBackend(anonymous)
    renderAt('/sv/khong-co')

    expect(await screen.findByText('Không tìm thấy nội dung này.')).toBeInTheDocument()
  })

  it('keeps planted payloads as text', async () => {
    const payload = PAYLOADS[0]
    fakeBackend(anonymous, {
      'GET /api/v1/students/minhtq': () =>
        json(publicStudent({ name: payload, school: payload, skillGroups: [{ category: payload, skills: [payload] }], team: { mentorDisplayName: payload, mentorLogin: 'hungdm', size: 1 } })),
      'GET /api/v1/students/minhtq/reputation': () => json({ count: 0, portfolio: [] }),
    })
    renderAt('/sv/minhtq')

    expect((await screen.findAllByText(new RegExp(payload.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')))).length).toBeGreaterThan(0)
    expect(document.querySelector('img[src="x"]')).toBeNull()
  })
})

describe('PUB-5, Ratings and Portfolio Entries (FR-51, FR-14)', () => {
  it('shows "Mới" for a Mentor with no closed Job, and the empty Portfolio line', async () => {
    fakeBackend(anonymous, {
      'GET /api/v1/mentors/hungdm': () => json(publicHung()),
      'GET /api/v1/mentors/hungdm/reputation': () => json({ count: 0, newcomer: true, portfolio: [] }),
    })
    renderAt('/mentor/hungdm')

    expect(await screen.findByText('Mới')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Hồ sơ năng lực' })).toBeInTheDocument()
    expect(screen.getByText('Chưa có công việc nào hoàn thành trên vibefix.')).toBeInTheDocument()
  })

  it('shows the Customers\' Ratings and the Portfolio Entries', async () => {
    fakeBackend(anonymous, {
      'GET /api/v1/mentors/hungdm': () => json(publicHung()),
      'GET /api/v1/mentors/hungdm/reputation': () =>
        json({
          average: 4.8,
          count: 6,
          newcomer: false,
          portfolio: [
            {
              title: 'Chỉnh giao diện điện thoại',
              role: 'MENTOR',
              jobTypes: ['MODIFICATION'],
              skills: ['React'],
              closedAt: '2026-08-20T03:00:00Z',
              milestones: 1,
              milestonesHandedOver: 1,
              reviewsWritten: 3,
              rating: 5,
              summary: 'Sửa giao diện cho điện thoại nhỏ.',
              productName: 'Sổ đặt bàn Quán Mây',
            },
          ],
        }),
    })
    renderAt('/mentor/hungdm')

    expect(await screen.findByText('4,8 sao')).toBeInTheDocument()
    expect(screen.getByText('(6 lượt từ khách hàng)')).toBeInTheDocument()
    expect(screen.queryByText('Mới')).toBeNull()
    expect(screen.getByText('Chỉnh giao diện điện thoại')).toBeInTheDocument()
    expect(screen.getByText('Chỉnh sửa · 08/2026 · 1 giai đoạn')).toBeInTheDocument()
    const card = within(screen.getByText('Chỉnh giao diện điện thoại').closest('li')!)
    expect(card.getByText('1 giai đoạn đã bàn giao · 3 lượt duyệt việc')).toBeInTheDocument()
    expect(card.getByText('5 sao')).toBeInTheDocument()
    expect(card.getByText('Sửa giao diện cho điện thoại nhỏ.')).toBeInTheDocument()
    expect(card.getByText('Sổ đặt bàn Quán Mây')).toBeInTheDocument()
    expect(card.getByText('React')).toBeInTheDocument()
  })

  it('says a revoked or locked Mentor takes no work now', async () => {
    fakeBackend(anonymous, {
      'GET /api/v1/mentors/hungdm': () => json(publicHung({ available: false })),
      'GET /api/v1/mentors/hungdm/reputation': () => json({ count: 0, newcomer: true, portfolio: [] }),
    })
    renderAt('/mentor/hungdm')

    expect(await screen.findByText('Người hướng dẫn này hiện không nhận việc')).toBeInTheDocument()
  })
})

describe('MEN-6, the link to PUB-4 (UX-DR83)', () => {
  it('opens a member\'s Sheet with the link to their public profile', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    const member = {
      accountId: 's1',
      githubLogin: 'minhtq',
      name: 'Trần Quang Minh',
      school: 'ĐH Bách khoa Hà Nội',
      major: 'Khoa học máy tính',
      skills: ['React'],
      verified: true,
      verifiedUntil: '2027-09-30',
    }
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/team': () =>
        json({
          status: 'OPEN',
          size: 1,
          sizeLimit: 10,
          invitesLeft: 20,
          members: [{ membershipId: 'ms1', student: member, joinedAt: '2026-10-01T03:00:00Z', onCrew: false }],
          joinRequests: [],
          invitations: [],
        }),
      'GET /api/v1/mentor/team/students/s1': () => json({ student: member, relation: 'MEMBER' }),
    })
    renderAt('/nhom')

    await user.click((await screen.findAllByRole('button', { name: /Trần Quang Minh/ }))[0])
    const sheet = await screen.findByRole('dialog')

    expect(await within(sheet).findByRole('link', { name: 'Xem hồ sơ công khai' })).toHaveAttribute('href', '/sv/minhtq')
  })
})
