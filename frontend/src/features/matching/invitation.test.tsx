import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const openJob = {
  id: 'j1',
  productId: 'p1',
  productName: 'Shop Gốm Bát Tràng',
  state: 'OPEN',
  title: 'Đánh giá bảo mật cửa hàng online',
  problem: 'Lo lộ dữ liệu.',
  jobTypes: ['SECURITY_TESTING'],
  skills: [],
  budget: 'FROM_2M_TO_5M',
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-12T03:00:00Z',
  version: 1,
  openedAt: '2026-10-12T03:00:00Z',
  lapsesAt: '2026-10-26T03:00:00Z',
}

const long = {
  accountId: 'm1',
  githubLogin: 'longtd',
  displayName: 'Trần Đức Long',
  yearsOfExperience: 10,
  skills: ['Supabase', 'Đăng nhập và phân quyền'],
  teamSize: 9,
  relation: 'NONE',
  available: true,
}

const ha = {
  accountId: 'm2',
  githubLogin: 'hatp',
  displayName: 'Phạm Thu Hà',
  yearsOfExperience: 6,
  skills: ['Supabase'],
  teamSize: 1,
  relation: 'INVITED',
  available: true,
}

const page = (items: object[]) => ({ items, page: 0, size: 20, total: items.length })

const quota = (left: number, limit?: string) => ({ left, limit, invitations: [] })

const publicLong = {
  accountId: 'm1',
  githubLogin: 'longtd',
  displayName: 'Trần Đức Long',
  experience: 'Từng làm kỹ sư bảo mật cho một ví điện tử.',
  yearsOfExperience: 10,
  memberSince: '2026-06-01T03:00:00Z',
  skillGroups: [],
  available: true,
  teamSize: 9,
  sizeLimit: 10,
  listedMembers: [],
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('CUS-8 "Tìm người hướng dẫn" (FR-14)', () => {
  it('searches by keyword, shows one batch of Ratings, and invites without a dialog', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let invited = false
    let sent: unknown
    const seen = fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () => json([]),
      'GET /api/v1/jobs/j1/invitations': () => json(quota(invited ? 8 : 9)),
      'GET /api/v1/jobs/j1/mentors': () => json(page([invited ? { ...long, relation: 'INVITED' } : long, ha])),
      'GET /api/v1/reputation/mentor-summaries': () =>
        json([
          { accountId: 'm1', average: 4.6, count: 14, newcomer: false },
          { accountId: 'm2', count: 0, newcomer: true },
        ]),
      'POST /api/v1/jobs/j1/invitations': async (request) => {
        sent = await request.json()
        invited = true
        return json({ left: 8 }, 201)
      },
    })
    renderAt('/cong-viec/j1/tim-nguoi?q=supabase')

    expect(await screen.findByRole('heading', { level: 2, name: 'Kết quả cho “supabase” (2)' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Tìm người hướng dẫn' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByText(/Còn 9 lời mời hôm nay cho công việc này\./)).toBeInTheDocument()
    expect(screen.getByText('Xếp theo mức độ khớp với từ khóa. Điểm chấm sao không làm thay đổi thứ tự.')).toBeInTheDocument()
    expect(screen.getByText('10 năm kinh nghiệm ·', { exact: false })).toBeInTheDocument()
    expect(await screen.findByText('4,6 sao (14 lượt)')).toBeInTheDocument()
    expect(screen.getByText('Mới')).toBeInTheDocument()
    const summaryCalls = seen.filter((request) => request.url.includes('/reputation/mentor-summaries'))
    expect(summaryCalls).toHaveLength(1)
    expect(new URL(summaryCalls[0].url).searchParams.getAll('id')).toEqual(['m1', 'm2'])
    expect(screen.getByRole('button', { name: 'Đã mời' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Mời ứng tuyển' }))

    await waitFor(() => expect(sent).toEqual({ mentorId: 'm1' }))
    expect(await screen.findByText('Đã gửi lời mời đến Trần Đức Long. Họ tự quyết định có ứng tuyển hay không.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Đã mời' })).toHaveLength(2))
    expect(await screen.findByText(/Còn 8 lời mời hôm nay/)).toBeInTheDocument()
    expect(screen.queryAllByRole('dialog').every((dialog) => dialog.dataset.type === 'success')).toBe(true)
  })

  it('reads "Chưa có đề xuất…" with no Recommendation, focuses the Search bar, and searches nothing', { timeout: 15_000 }, async () => {
    const seen = fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () => json([]),
      'GET /api/v1/jobs/j1/invitations': () => json(quota(10)),
      'GET /api/v1/jobs/j1/recommendations': () => json([]),
    })
    renderAt('/cong-viec/j1/tim-nguoi')

    expect(
      await screen.findByText('Chưa có đề xuất cho công việc này. Hãy tìm theo kỹ năng, ví dụ: React hoặc Supabase.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Tìm người hướng dẫn theo kỹ năng hoặc tên. Gõ có dấu hoặc không dấu đều được.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tìm' })).toBeDisabled()
    await waitFor(() => expect(screen.getByRole('searchbox', { name: 'Tìm người hướng dẫn' })).toHaveFocus())
    expect(seen.some((request) => request.url.includes('/mentors'))).toBe(false)
  })

  it('disables every Invite button at a used-up limit and says which', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () => json([]),
      'GET /api/v1/jobs/j1/invitations': () => json(quota(0, 'INVITE_LIMIT_JOB_POST')),
      'GET /api/v1/jobs/j1/mentors': () => json(page([long])),
      'GET /api/v1/reputation/mentor-summaries': () => json([]),
    })
    renderAt('/cong-viec/j1/tim-nguoi?q=supabase')

    expect(await screen.findByText(/Hôm nay bạn đã gửi đủ 10 lời mời cho công việc này\./)).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Mời ứng tuyển' })).toBeDisabled()
  })

  it('disables the Invite button of a Mentor without GitHub linked, with the line that says why', { timeout: 15_000 }, async () => {
    const unlinked = { ...long, githubLogin: undefined, yearsOfExperience: undefined, available: false }
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () => json([]),
      'GET /api/v1/jobs/j1/invitations': () => json(quota(9)),
      'GET /api/v1/jobs/j1/mentors': () => json(page([unlinked])),
      'GET /api/v1/reputation/mentor-summaries': () => json([]),
    })
    renderAt('/cong-viec/j1/tim-nguoi?q=supabase')

    const button = await screen.findByRole('button', { name: 'Mời ứng tuyển' })
    expect(button).toBeDisabled()
    expect(button).toHaveAccessibleDescription('Người hướng dẫn chưa liên kết GitHub')
    expect(screen.getByText(/Kinh nghiệm: Chưa khai/)).toBeInTheDocument()
  })

  it('opens PUB-5 in a Sheet with the Invite button and returns focus to the card', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () => json([]),
      'GET /api/v1/jobs/j1/invitations': () => json(quota(9)),
      'GET /api/v1/jobs/j1/mentors': () => json(page([long])),
      'GET /api/v1/reputation/mentor-summaries': () => json([]),
      'GET /api/v1/mentors/longtd': () => json(publicLong),
      'GET /api/v1/mentors/longtd/reputation': () => json({ count: 0, newcomer: true, portfolio: [] }),
    })
    renderAt('/cong-viec/j1/tim-nguoi?q=supabase')

    const open = await screen.findByRole('button', { name: 'Xem hồ sơ' })
    await user.click(open)
    const sheet = await screen.findByRole('dialog', { name: 'Trần Đức Long' })
    expect(await within(sheet).findByText('Từng làm kỹ sư bảo mật cho một ví điện tử.')).toBeInTheDocument()
    expect(within(sheet).getByText(/vibefix không hiện cách liên lạc/)).toBeInTheDocument()
    expect(within(sheet).getByText('Còn 9 lời mời hôm nay cho công việc này')).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: 'Mời ứng tuyển' })).toBeEnabled()

    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(open).toHaveFocus())
  })

  it('sends a Job past Open to "Đơn ứng tuyển", which goes on to the overview', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () =>
        json({ ...openJob, state: 'SELECTING', version: 2, mentor: { displayName: 'Đỗ Mạnh Hùng' }, canCancelSelection: false }),
      'GET /api/v1/jobs/j1/applications': () => json([]),
    })
    const router = renderAt('/cong-viec/j1/tim-nguoi')

    await waitFor(() => expect(router.state.location.pathname).toBe('/cong-viec/j1'))
  })

  it('marks an invited Mentor\'s Application with "Đã mời"', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () =>
        json([
          {
            id: 'a1',
            status: 'SUBMITTED',
            priceVnd: 2_500_000,
            timelineDays: 6,
            approach: 'Kiểm tra quyền truy cập.',
            submittedAt: '2026-10-16T03:00:00Z',
            mentor: { displayName: 'Trần Đức Long', githubLogin: 'longtd', yearsOfExperience: 10, teamSize: 9 },
            invited: true,
          },
        ]),
      'GET /api/v1/mentors/longtd/reputation': () => json({ count: 0, newcomer: true, portfolio: [] }),
    })
    renderAt('/cong-viec/j1/ung-tuyen')

    const card = (await screen.findByText('Trần Đức Long')).closest('li') as HTMLElement
    expect(within(card).getByText('Đã mời')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Tìm người hướng dẫn' })).toHaveAttribute('href', '/cong-viec/j1/tim-nguoi')
  })
})

describe('MEN-3 with an invitation (FR-14)', () => {
  const post = {
    id: 'j1',
    phase: 'FINDING_MENTOR',
    need: 'MENTOR',
    title: 'Sửa lỗi mất đơn hàng',
    productName: 'Shop Gốm Bát Tràng',
    productDescription: 'Cửa hàng gốm.',
    productTools: ['LOVABLE'],
    productOpenFindings: 0,
    problem: 'Đơn không hiện.',
    jobTypes: ['BUG_FIX'],
    skills: [],
    openedAt: '2026-10-15T03:00:00Z',
    lapsesAt: '2026-10-29T03:00:00Z',
    screenshots: [],
  }
  const invitation = (status: string) => ({
    id: 'i1',
    jobTitle: 'Sửa lỗi mất đơn hàng',
    status,
    invitedAt: '2026-10-16T03:00:00Z',
  })

  it('shows who invited the Mentor while the invitation is open', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts/j1': () => json(post),
      'GET /api/v1/mentor/job-posts/j1/invitation': () => json(invitation('OPEN')),
      'GET /api/v1/mentor/job-posts/j1/customer-rating': () => json({ count: 0 }),
      'GET /api/v1/mentor/capacity': () =>
        json({ teamSize: 6, freeStudents: 2, activeJobs: 0, jobLimit: 3, reasons: [] }),
      'GET /api/v1/mentor/applications': () => json([]),
    })
    renderAt('/tim-viec/j1')

    expect(
      await screen.findByText('Khách hàng đã mời bạn ứng tuyển công việc này. Bạn tự quyết định; lời mời không giữ chỗ cho bạn.'),
    ).toBeInTheDocument()
  })

  it('shows the lapsed card instead of the not-found screen', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/mentor/job-posts/j1/invitation': () => json(invitation('LAPSED')),
    })
    renderAt('/tim-viec/j1')

    expect(
      await screen.findByRole('heading', { name: 'Lời mời này đã hết hiệu lực vì công việc không còn nhận ứng tuyển.' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Khách hàng đã chọn người hướng dẫn khác hoặc tin đã đóng.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Tìm việc khác' })).toHaveAttribute('href', '/tim-viec')
  })
})
