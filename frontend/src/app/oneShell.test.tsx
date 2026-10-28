import { render, screen, waitFor, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, heldJobs, json, signedInUser } from '@/test/fakeBackend'

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

/** Job A, which the user posted and where they are the Customer. */
const jobA = {
  id: 'a',
  productId: 'p1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'SELECTING',
  title: 'Việc A tôi đăng',
  jobTypes: ['BUG_FIX'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-15T03:00:00Z',
  updatedAt: '2026-10-18T03:00:00Z',
  version: 2,
  mentor: { displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm' },
  selectedAt: '2026-10-18T03:00:00Z',
  ndaDueAt: '2026-10-21T03:00:00Z',
  canCancelSelection: false,
  agreementShared: false,
  agreementConfirmed: false,
}

/** Job B, which the user mentors, waiting for their NDA. */
const jobB = {
  id: 'b',
  title: 'Việc B tôi hướng dẫn',
  productName: 'Shop Gốm Bát Tràng',
  state: 'SELECTING',
  version: 2,
  jobTypes: ['BUG_FIX'],
  selectedAt: '2026-10-18T03:00:00Z',
  ndaDueAt: '2026-10-21T03:00:00Z',
  codeJob: false,
  commitEmailNoticeAcknowledged: true,
}

const todo = (key: string, extra: Record<string, unknown> = {}) => ({
  id: crypto.randomUUID(),
  key,
  tier: 2,
  params: {},
  link: '/ho-so/xac-minh',
  deadlineNear: false,
  openedAt: '2026-10-25T00:00:00Z',
  ...extra,
})

/** The user is the Customer of Job A and the Mentor of Job B (Story 20.6's "Two roles"). */
function twoRoles(extra: Record<string, (request: Request) => Response | Promise<Response>> = {}) {
  return fakeBackend(signedInUser, {
    ...heldJobs({ jobId: 'a', title: jobA.title, role: 'CUSTOMER' }, { jobId: 'b', title: jobB.title, role: 'MENTOR' }),
    'GET /api/v1/jobs/a/role': () => json({ role: 'CUSTOMER' }),
    'GET /api/v1/jobs/b/role': () => json({ role: 'MENTOR' }),
    'GET /api/v1/jobs/a': () => json(jobA),
    'GET /api/v1/mentor/jobs/b': () => json(jobB),
    ...extra,
  })
}

describe('JOB-H, the role in each Job (UX-DR87, Story 20.6)', () => {
  it('says "Bạn là: Khách hàng" on the Job the user posted', { timeout: 15_000 }, async () => {
    twoRoles()
    renderAt('/cong-viec/a')

    expect(await screen.findByRole('heading', { level: 1, name: jobA.title })).toBeInTheDocument()
    expect(await screen.findByText('Bạn là: Khách hàng')).toBeInTheDocument()
  })

  it('says "Bạn là: Người hướng dẫn" on the Job the same user mentors, in the Mentor\'s words', { timeout: 15_000 }, async () => {
    twoRoles()
    renderAt('/cong-viec/b')

    expect(await screen.findByRole('heading', { level: 1, name: jobB.title })).toBeInTheDocument()
    expect(await screen.findByText('Bạn là: Người hướng dẫn')).toBeInTheDocument()
    expect(screen.getByText('Chờ bạn ký cam kết bảo mật')).toBeInTheDocument()
  })

  it('says "Bạn là: Thành viên nhóm · đã rời" to a member who left', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      'GET /api/v1/jobs/c/role': () => json({ role: 'CREW', crewKind: 'FORMER' }),
      'GET /api/v1/boards/c/progress': () =>
        json({
          boardId: 'c',
          title: 'Việc C đã rời',
          state: 'ACTIVE',
          productName: 'Sổ đặt bàn Quán Mây',
          mentorName: 'Đỗ Mạnh Hùng',
          audience: 'FORMER',
          milestones: [],
          pages: [],
          crew: [],
          mine: [],
          assessment: false,
        }),
    })
    renderAt('/cong-viec/c')

    expect(await screen.findByRole('heading', { level: 1, name: 'Việc C đã rời' })).toBeInTheDocument()
    expect(await screen.findByText('Bạn là: Thành viên nhóm · đã rời')).toBeInTheDocument()
  })
})

describe('the to-dos of every role (UX-DR88, AD-27, Story 20.6)', () => {
  it('gives each to-do its context line: the Job and the role, or "Tài khoản"', { timeout: 15_000 }, async () => {
    twoRoles({
      'GET /api/v1/todos': () =>
        json({
          items: [
            todo('identity.link_github', { tier: 1 }),
            todo('job.draft_agreement', { params: { job: jobB.title }, link: '/cong-viec/b/thoa-thuan', jobId: 'b', role: 'MENTOR' }),
            todo('job.answer_closure', {
              params: { job: 'Việc A cũ', date: '2026-11-01' },
              link: '/cong-viec/a',
              jobId: 'a',
            }),
          ],
          total: 3,
        }),
    })
    renderAt('/viec-can-lam')

    const list = await screen.findByRole('list', { name: 'Việc cần làm của bạn' })
    const items = within(list).getAllByRole('link')
    expect(items[0]).toHaveTextContent(/^Tài khoản/)
    await waitFor(() => expect(items[1]).toHaveTextContent(/^Việc B tôi hướng dẫn · Bạn là Người hướng dẫn/))
    // A Job's to-do without a stored role names the Job alone, its title from "Công việc".
    expect(items[2]).toHaveTextContent(/^Việc A tôi đăng/)
    expect(items[2]).not.toHaveTextContent('Bạn là')
    const strip = screen.getByRole('region', { name: 'Việc cần làm tiếp theo' })
    expect(strip).toHaveTextContent('Tài khoản')
  })

  it("shows only that Job's to-dos in the strip inside a Job, dense where the user is the Mentor", { timeout: 15_000 }, async () => {
    const asked: (string | null)[] = []
    twoRoles({
      'GET /api/v1/todos': (request) => {
        const jobId = new URL(request.url, 'http://localhost').searchParams.get('jobId')
        asked.push(jobId)
        const mine = todo('job.draft_agreement', {
          params: { job: jobB.title },
          link: '/cong-viec/b/thoa-thuan',
          jobId: 'b',
          role: 'MENTOR',
        })
        const account = todo('identity.link_github', { tier: 1 })
        return json(jobId === 'b' ? { items: [mine], total: 1 } : { items: [account, mine], total: 2 })
      },
    })
    renderAt('/cong-viec/b')

    const strip = await screen.findByRole('region', { name: 'Việc cần làm tiếp theo' })
    await waitFor(() => expect(strip).toHaveTextContent('Việc B tôi hướng dẫn · Bạn là Người hướng dẫn'))
    expect(strip).not.toHaveTextContent('Tài khoản')
    expect(asked).toContain('b')
    expect(within(strip).getByRole('link', { name: 'Làm ngay' })).toHaveClass('min-h-control-compact')
  })

  it('keeps the Customer sizes in the strip outside a Job (UX-DR111)', { timeout: 15_000 }, async () => {
    twoRoles({
      'GET /api/v1/todos': () =>
        json({
          items: [todo('job.draft_agreement', { params: { job: jobB.title }, link: '/cong-viec/b/thoa-thuan', jobId: 'b', role: 'MENTOR' })],
          total: 1,
        }),
    })
    renderAt('/viec-can-lam')

    const strip = await screen.findByRole('region', { name: 'Việc cần làm tiếp theo' })
    expect(within(strip).getByRole('link', { name: 'Làm ngay' })).toHaveClass('min-h-tap-min')
  })
})
