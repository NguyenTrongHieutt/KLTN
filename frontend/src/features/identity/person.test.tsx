import { render, screen, waitFor, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { anonymous, fakeBackend, json, signedInUser } from '@/test/fakeBackend'
import { PAYLOADS } from '@/test/payloads'
import { emptyRole, personReputation, publicPerson, verifiedFacts } from '@/test/people'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const entry = (role: string, title: string, overrides: object = {}) => ({
  title,
  role,
  jobTypes: ['BUG_FIX'],
  skills: ['React'],
  closedAt: '2026-09-20T03:00:00Z',
  milestones: 2,
  workItemsApproved: role === 'CREW' ? 4 : undefined,
  milestonesHandedOver: role === 'MENTOR' ? 2 : undefined,
  reviewsWritten: role === 'MENTOR' ? 5 : undefined,
  ...overrides,
})

/** Hùng has been a Mentor and a Crew member: each history with its own Ratings and Portfolio Entries. */
const bothRoles = personReputation({
  mentor: {
    jobs: 3,
    jobTypes: [{ jobType: 'BUG_FIX', count: 2 }],
    average: 4.8,
    count: 6,
    portfolio: [entry('MENTOR', 'Sửa lỗi đặt bàn', { productName: 'Sổ đặt bàn Quán Mây', summary: 'Sửa đặt trùng bàn.' })],
  },
  crew: { jobs: 1, jobTypes: [{ jobType: 'BUG_FIX', count: 1 }], average: 4, count: 1, portfolio: [entry('CREW', 'Thêm thanh toán')] },
  newcomer: false,
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('PUB-4, one public profile (UX-DR96, Story 21.3)', () => {
  it('shows the Verified facts in order, the typed profile marked "Tự khai", and two histories with their own Ratings', { timeout: 15_000 }, async () => {
    fakeBackend(anonymous, {
      'GET /api/v1/users/hungdm': () =>
        json(
          publicPerson('hungdm', {
            verified: verifiedFacts('hungdm', { school: 'Đại học Bách khoa Hà Nội', name: 'Đỗ Mạnh Hùng', major: 'Phần mềm' }),
            links: ['https://hung.dev/gioi-thieu'],
            repositories: ['hungdm/dat-ban'],
          }),
        ),
      'GET /api/v1/users/hungdm/reputation': () => json(bothRoles),
    })
    renderAt('/nguoi-dung/hungdm')

    expect(await screen.findByRole('heading', { level: 1, name: 'Đỗ Mạnh Hùng' })).toBeInTheDocument()
    const facts = within(screen.getByRole('region', { name: 'Đã xác minh' })).getAllByRole('listitem')
    expect(facts.map((fact) => fact.textContent)).toEqual([
      expect.stringMatching(/^GitHub@hungdmXác minh qua GitHub · 10\/2026/),
      expect.stringMatching(/^Sinh viên đã xác minhXác minh qua email trường · 09\/2026Đại học Bách khoa Hà Nội · Đỗ Mạnh Hùng · Phần mềm \(Khai khi xác minh\)/),
      'LinkedInChưa xác minh',
    ])
    const typed = screen.getByRole('region', { name: 'Tự giới thiệu' })
    expect(within(typed).getByText('Tự khai')).toBeInTheDocument()
    expect(within(typed).getByText('8 năm kinh nghiệm')).toBeInTheDocument()
    expect(within(typed).getByText('Kỹ sư phần mềm.')).toBeInTheDocument()
    expect(within(typed).getByRole('link', { name: 'https://hung.dev/gioi-thieu' })).toHaveAttribute('target', '_blank')
    expect(within(typed).getByRole('link', { name: 'hungdm/dat-ban' })).toHaveAttribute('href', 'https://github.com/hungdm/dat-ban')
    expect(screen.getByText('Giao diện web')).toBeInTheDocument()
    expect(screen.getByText('Tham gia vibefix từ 06/2026')).toBeInTheDocument()

    const asMentor = await screen.findByRole('region', { name: 'Khi làm người hướng dẫn' })
    expect(within(asMentor).getByText('3 công việc hoàn thành')).toBeInTheDocument()
    expect(within(asMentor).getByText('4,8 sao')).toBeInTheDocument()
    expect(within(asMentor).getByText('(6 lượt từ khách hàng)')).toBeInTheDocument()
    expect(within(asMentor).getByText('Sửa lỗi đặt bàn')).toBeInTheDocument()
    expect(within(asMentor).getByText('Sổ đặt bàn Quán Mây')).toBeInTheDocument()
    const asCrew = screen.getByRole('region', { name: 'Khi làm thành viên nhóm' })
    expect(within(asCrew).getByText('(1 lượt từ người hướng dẫn)')).toBeInTheDocument()
    expect(within(asCrew).getByText('Thêm thanh toán')).toBeInTheDocument()
    expect(within(asCrew).queryByText('Sửa lỗi đặt bàn')).toBeNull()
    expect(screen.getByText(/không hiện cách liên lạc, số dư hay email trường/)).toBeInTheDocument()
    expect(screen.queryByText('Đây là hồ sơ người khác thấy.')).toBeNull()
  })

  it('shows the badge alone when the owner hides the student facts, and "Chưa có" for a role never held', async () => {
    fakeBackend(anonymous, {
      'GET /api/v1/users/vy': () =>
        json(publicPerson('vy', { displayName: 'Thảo Vy', verified: verifiedFacts('vy', {}), yearsOfExperience: undefined })),
      'GET /api/v1/users/vy/reputation': () => json(personReputation()),
    })
    renderAt('/nguoi-dung/vy')

    const facts = within(await screen.findByRole('region', { name: 'Đã xác minh' })).getAllByRole('listitem')
    expect(facts[1].textContent).toBe('Sinh viên đã xác minhXác minh qua email trường · 09/2026')
    expect(screen.queryByText(/Khai khi xác minh/)).toBeNull()
    expect(screen.getByText('Kinh nghiệm: Chưa khai')).toBeInTheDocument()
    expect(within(await screen.findByRole('region', { name: 'Khi làm người hướng dẫn' })).getByText('Chưa có')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Khi làm thành viên nhóm' })).getByText('Chưa có')).toBeInTheDocument()
  })

  it('shows only that a locked person is not active', async () => {
    const seen = fakeBackend(anonymous, {
      'GET /api/v1/users/khoa': () => json({ locked: true, verified: [], links: [], repositories: [], skillGroups: [], own: false }),
    })
    renderAt('/nguoi-dung/khoa')

    expect(await screen.findByText('Người dùng này hiện không hoạt động trên vibefix')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Đã xác minh' })).toBeNull()
    expect(seen.some((request) => request.url.includes('/reputation'))).toBe(false)
  })

  it('tells the owner it is the page others see, with "Sửa hồ sơ"', async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/users/octo-cat': () => json(publicPerson('octo-cat', { own: true })),
      'GET /api/v1/users/octo-cat/reputation': () => json(personReputation({ mentor: emptyRole() })),
    })
    renderAt('/nguoi-dung/octo-cat')

    expect(await screen.findByText('Đây là hồ sơ người khác thấy.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sửa hồ sơ' })).toHaveAttribute('href', '/ho-so')
    expect(screen.queryByRole('button', { name: /Thao tác khác với hồ sơ của/ })).toBeNull()
  })

  it("offers a signed-in visitor the report menu on someone else's profile (FR-63)", async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/users/hungdm': () => json(publicPerson('hungdm')),
      'GET /api/v1/users/hungdm/reputation': () => json(personReputation()),
    })
    renderAt('/nguoi-dung/hungdm')

    expect(await screen.findByRole('button', { name: 'Thao tác khác với hồ sơ của Đỗ Mạnh Hùng' })).toBeInTheDocument()
    expect(screen.queryByText('Đây là hồ sơ người khác thấy.')).toBeNull()
  })

  it('redirects the old Mentor and Student addresses to PUB-4', async () => {
    fakeBackend(anonymous, {
      'GET /api/v1/users/hungdm': () => json(publicPerson('hungdm')),
      'GET /api/v1/users/hungdm/reputation': () => json(personReputation()),
    })
    const mentor = renderAt('/mentor/hungdm')
    await waitFor(() => expect(mentor.state.location.pathname).toBe('/nguoi-dung/hungdm'))
    expect(await screen.findByRole('heading', { level: 1, name: 'Đỗ Mạnh Hùng' })).toBeInTheDocument()
  })

  it('redirects the old Student address too', async () => {
    fakeBackend(anonymous, {
      'GET /api/v1/users/minhtq': () => json(publicPerson('minhtq', { displayName: 'Trần Quang Minh' })),
      'GET /api/v1/users/minhtq/reputation': () => json(personReputation()),
    })
    const student = renderAt('/sv/minhtq')
    await waitFor(() => expect(student.state.location.pathname).toBe('/nguoi-dung/minhtq'))
  })

  it('shows the not-found screen for a username no linked account holds', async () => {
    fakeBackend(anonymous)
    renderAt('/nguoi-dung/khong-co')

    expect(await screen.findByText('Không tìm thấy nội dung này.')).toBeInTheDocument()
  })

  it('keeps planted payloads as text (SG-30)', async () => {
    const payload = PAYLOADS[0]
    fakeBackend(anonymous, {
      'GET /api/v1/users/x': () =>
        json(
          publicPerson('x', {
            displayName: payload,
            experience: payload,
            verified: verifiedFacts('x', { school: payload, name: payload, major: payload }),
            skillGroups: [{ category: payload, skills: [payload] }],
          }),
        ),
      'GET /api/v1/users/x/reputation': () =>
        json(personReputation({ mentor: { ...emptyRole(), jobs: 1, portfolio: [entry('MENTOR', payload, { summary: payload })] } })),
    })
    renderAt('/nguoi-dung/x')

    expect(await screen.findByRole('heading', { level: 1, name: payload })).toBeInTheDocument()
    expect(document.querySelector('img[src="x"]')).toBeNull()
  })
})
