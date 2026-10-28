import { render, screen, within } from '@testing-library/react'
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

afterEach(() => {
  vi.unstubAllGlobals()
})

const task = (n: number, title: string, assignee: { accountId: string; name: string }, estimateHours: number) => ({
  id: `w${n}`,
  key: `SDB-${n}`,
  type: 'TASK',
  title,
  description: '',
  status: 'TODO',
  milestoneId: 'm1',
  seeded: false,
  estimateHours,
  checklistDone: 0,
  checklistTotal: 0,
  assignee,
  version: 1,
})

const vy = { accountId: 's1', name: 'Trần Thảo Vy' }
const tung = { accountId: 's2', name: 'Lê Minh Tùng' }

const board = (overrides: object = {}) => ({
  boardId: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  code: 'SDB',
  viewerId: 'mentor1',
  mentor: true,
  readOnly: false,
  milestones: [{ milestoneId: 'm1', number: 1, title: 'Sửa lỗi trùng giờ', held: true }],
  crew: [
    { ...vy, available: true },
    { ...tung, available: true },
    { accountId: 's3', name: 'Nguyễn Văn Minh', available: false },
  ],
  workload: [
    { ...vy, openItems: 2, estimateHours: 6.5 },
    { ...tung, openItems: 1, estimateHours: 3 },
  ],
  items: [
    task(1, 'Sửa trùng giờ', vy, 2.5),
    task(2, 'Thêm kiểm thử', vy, 4),
    task(3, 'Email nhắc lịch', tung, 3),
  ],
  ...overrides,
})

const mentorJob = {
  id: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  version: 12,
  jobTypes: ['BUG_FIX'],
  selectedAt: '2026-10-18T03:00:00Z',
  codeJob: true,
  commitEmailNoticeAcknowledged: true,
  agreementConfirmed: true,
}

describe('Story 11.2: the Crew workload on JOB-2 (FR-23, FR-26, AD-41, UX-DR37)', () => {
  it('shows the Mentor each member\'s open work, and selecting one filters the Board', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/work-items': () => json(board()),
    })
    renderAt('/cong-viec/j1/bang')

    const line = await screen.findByRole('group', { name: 'Khối lượng việc' })
    const member = within(line).getByRole('button', { name: 'Trần Thảo Vy · 2 việc · 6,5 giờ' })
    expect(within(line).getByRole('button', { name: 'Lê Minh Tùng · 1 việc · 3 giờ' })).toBeInTheDocument()
    const todo = screen.getByRole('region', { name: /Cần làm/ })
    expect(within(todo).getAllByRole('listitem')).toHaveLength(3)

    await user.click(member)
    expect(member).toHaveAttribute('aria-pressed', 'true')
    expect(within(todo).getAllByRole('listitem')).toHaveLength(2)
    expect(within(todo).queryByText('Email nhắc lịch')).toBeNull()
    expect(screen.getByRole('combobox', { name: 'Người làm' })).toHaveValue('s1')

    await user.click(member)
    expect(member).toHaveAttribute('aria-pressed', 'false')
    expect(within(todo).getAllByRole('listitem')).toHaveLength(3)
  })

  it('lists a member whose lock is not carried out yet disabled in the assignee picker', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/work-items': () => json(board()),
    })
    renderAt('/cong-viec/j1/bang')

    await user.click(await screen.findByRole('button', { name: 'Thêm việc' }))
    const sheet = await screen.findByRole('dialog', { name: 'Thêm việc' })
    const picker = within(sheet).getByRole('combobox', { name: 'Người làm' })
    expect(within(picker).getByRole('option', { name: 'Trần Thảo Vy' })).toBeEnabled()
    expect(within(picker).getByRole('option', { name: 'Nguyễn Văn Minh (đã rời nhóm)' })).toBeDisabled()
  })

  it('hides the line when the Board sends no workload: a Crew member, or the flag off', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/work-items': () => json(board({ workload: [] })),
    })
    renderAt('/cong-viec/j1/bang')

    expect(await screen.findByRole('region', { name: /Cần làm/ })).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Khối lượng việc' })).toBeNull()
  })
})
