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
  title: 'Sửa lỗi mất đơn hàng',
  problem: 'Đơn không hiện.',
  jobTypes: ['BUG_FIX'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-12T03:00:00Z',
  version: 1,
  openedAt: '2026-10-12T03:00:00Z',
  lapsesAt: '2026-10-26T03:00:00Z',
}

const thao = {
  accountId: 'm1',
  githubLogin: 'thaonp',
  displayName: 'Nguyễn Phương Thảo',
  yearsOfExperience: 3,
  skills: ['React', 'Supabase'],
  teamSize: 2,
  relation: 'NONE',
  available: true,
  signals: { sharedSkills: ['React', 'Supabase'], jobTypeMatches: [], newcomer: true },
}

const long = {
  accountId: 'm2',
  githubLogin: 'longtd',
  displayName: 'Trần Đức Long',
  yearsOfExperience: 10,
  skills: ['Supabase'],
  teamSize: 9,
  relation: 'APPLIED',
  available: true,
  signals: { sharedSkills: ['Supabase'], jobTypeMatches: [{ jobType: 'BUG_FIX', count: 2 }], newcomer: false },
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('CUS-8 "Đề xuất cho công việc này" (FR-14, AD-17)', () => {
  it('shows the platform-written signals and invites from a Recommendation', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let sent: unknown
    fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () => json([]),
      'GET /api/v1/jobs/j1/invitations': () => json({ left: 10, invitations: [] }),
      'GET /api/v1/jobs/j1/recommendations': () => json([thao, long]),
      'GET /api/v1/reputation/mentor-summaries': () =>
        json([
          { accountId: 'm1', count: 0, newcomer: true },
          { accountId: 'm2', average: 4.6, count: 14, newcomer: false },
        ]),
      'POST /api/v1/jobs/j1/invitations': async (request) => {
        sent = await request.json()
        return json({ left: 9 }, 201)
      },
    })
    renderAt('/cong-viec/j1/tim-nguoi')

    const section = await screen.findByRole('region', { name: 'Đề xuất cho công việc này' })
    const cards = within(section).getAllByRole('listitem').filter((item) => item.parentElement?.parentElement === section)
    expect(cards).toHaveLength(2)
    expect(within(cards[0]).getByText('Hợp với React, Supabase')).toBeInTheDocument()
    expect(within(cards[0]).getByText('Mới')).toBeInTheDocument()
    expect(within(cards[1]).getByText('Đã làm 2 công việc Sửa lỗi')).toBeInTheDocument()
    expect(await within(cards[1]).findByText('4,6 sao (14 lượt)')).toBeInTheDocument()
    expect(within(cards[1]).getByRole('button', { name: 'Đã ứng tuyển' })).toBeDisabled()
    expect(screen.queryByText(/AI/)).toBeNull()

    await user.click(within(cards[0]).getByRole('button', { name: 'Mời ứng tuyển' }))

    await waitFor(() => expect(sent).toEqual({ mentorId: 'm1' }))
  })

  it('hides the Recommendations while a keyword is active', { timeout: 15_000 }, async () => {
    const seen = fakeBackend(signedInUser, {
      ...inEveryJob('CUSTOMER'),
      'GET /api/v1/jobs/j1': () => json(openJob),
      'GET /api/v1/jobs/j1/applications': () => json([]),
      'GET /api/v1/jobs/j1/invitations': () => json({ left: 10, invitations: [] }),
      'GET /api/v1/jobs/j1/mentors': () => json({ items: [], page: 0, size: 20, total: 0 }),
    })
    renderAt('/cong-viec/j1/tim-nguoi?q=react')

    expect(await screen.findByText('Không tìm thấy người hướng dẫn nào cho “react”.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Đề xuất cho công việc này' })).toBeNull()
    expect(seen.some((request) => request.url.includes('/recommendations'))).toBe(false)
  })
})

describe('MEN-2 "Đề xuất cho bạn" (FR-14, AD-17)', () => {
  const recommended = {
    id: 'j7',
    title: 'Thêm đăng nhập bằng Zalo cho app học từ vựng',
    productName: 'Học Từ Mỗi Ngày',
    jobTypes: ['NEW_FEATURE'],
    budget: 'FROM_5M_TO_10M',
    openedAt: '2026-10-16T03:00:00Z',
    invited: true,
    signals: { sharedSkills: ['Supabase'], jobTypeMatches: [], newcomer: false },
  }
  const handlers = {
    'GET /api/v1/mentor/job-posts': () => json({ items: [], page: 0, size: 20, total: 0 }),
    'GET /api/v1/mentor/capacity': () => json({ teamSize: 6, freeStudents: 2, activeJobs: 0, jobLimit: 3, reasons: [] }),
    'GET /api/v1/mentor/applications': () => json([]),
    'GET /api/v1/mentor/recommendations': () => json([recommended]),
  }

  it('lists Recommendations above the Job Posts with "Bạn được mời" and the chips', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, { ...inEveryJob('MENTOR'), ...handlers })
    renderAt('/tim-viec')

    const section = await screen.findByRole('region', { name: 'Đề xuất cho bạn' })
    const card = within(section).getByRole('link', { name: /Thêm đăng nhập bằng Zalo/ })
    expect(card).toHaveAttribute('href', '/tim-viec/j7')
    expect(within(card).getByText('Bạn được mời')).toBeInTheDocument()
    expect(within(card).getByText('Hợp với Supabase')).toBeInTheDocument()
  })

  it('hides them while a keyword is active', { timeout: 15_000 }, async () => {
    const seen = fakeBackend(signedInUser, { ...inEveryJob('MENTOR'), ...handlers })
    renderAt('/tim-viec?q=zalo')

    await waitFor(() => expect(seen.some((request) => request.url.includes('/mentor/job-posts'))).toBe(true))
    expect(screen.queryByRole('region', { name: 'Đề xuất cho bạn' })).toBeNull()
    expect(seen.some((request) => request.url.includes('/mentor/recommendations'))).toBe(false)
  })
})
