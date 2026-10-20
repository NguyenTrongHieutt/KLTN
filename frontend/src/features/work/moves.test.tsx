import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'

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

const card = (id: string, overrides: object = {}) => ({
  id,
  key: `SDB-${id}`,
  type: 'TASK',
  title: `Việc ${id}`,
  description: '',
  status: 'TODO',
  milestoneId: 'm1',
  seeded: false,
  checklistDone: 0,
  checklistTotal: 0,
  assignee: { accountId: 'vy', name: 'Trần Thảo Vy' },
  version: 1,
  ...overrides,
})

const board = (overrides: object = {}) => ({
  boardId: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  code: 'SDB',
  viewerId: 'vy',
  mentor: false,
  readOnly: false,
  milestones: [
    { milestoneId: 'm1', number: 1, title: 'Sửa lỗi trùng giờ', held: true },
    { milestoneId: 'm2', number: 2, title: 'Email nhắc lịch', held: false },
  ],
  crew: [],
  items: [
    card('1'),
    card('2', { milestoneId: 'm2' }),
    card('3', { assignee: { accountId: 'tung', name: 'Lê Minh Tùng' } }),
    card('4', { status: 'IN_REVIEW', type: 'BUG' }),
  ],
  ...overrides,
})

describe('Board moves on JOB-2 (FR-24, AD-39, UX-DR36, UX-DR37)', () => {
  it('lets the assignee move a card at once and offers only their moves', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const moved: unknown[] = []
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/work-items': () => json(board()),
      'POST /api/v1/work-items/1/move': async (request) => {
        moved.push(await request.json())
        return new Promise(() => undefined)
      },
    })
    renderAt('/cong-viec/j1/bang')

    expect(await screen.findByRole('button', { name: 'Chuyển SDB-1 sang…' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Chuyển SDB-3 sang…' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Chuyển SDB-4 sang…' })).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Chuyển SDB-2 sang…' }))
    const blocked = await screen.findByRole('menuitem', { name: /Đang làm/ })
    expect(blocked).toHaveAttribute('aria-disabled', 'true')
    expect(within(blocked).getByText('Giai đoạn này chưa được thanh toán.')).toBeInTheDocument()
    await user.keyboard('{Escape}')

    await user.click(screen.getByRole('button', { name: 'Chuyển SDB-1 sang…' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Đang làm' }))
    await waitFor(() => expect(moved).toEqual([{ to: 'IN_PROGRESS', version: 1 }]))
    const inProgress = screen.getByRole('region', { name: /^Đang làm/ })
    expect(within(inProgress).getByText('Việc 1')).toBeInTheDocument()
  })

  it('moves the card back with a toast when the server refuses', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/work-items': () => json(board()),
      'POST /api/v1/work-items/1/move': () =>
        json({ status: 422, code: 'GUARD_FAILED', reason: 'MILESTONE_NOT_HELD' }, 422),
    })
    renderAt('/cong-viec/j1/bang')

    await user.click(await screen.findByRole('button', { name: 'Chuyển SDB-1 sang…' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Đang làm' }))

    expect((await screen.findAllByText('Giai đoạn này chưa được thanh toán.')).length).toBeGreaterThan(0)
    await waitFor(() =>
      expect(within(screen.getByRole('region', { name: /^Cần làm/ })).getByText('Việc 1')).toBeInTheDocument(),
    )
  })

  it('filters by Milestone, type, and "Của tôi", and starts phones on the viewer\'s column', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/work-items': () => json(board()),
    })
    renderAt('/cong-viec/j1/bang')

    expect(await screen.findByText('Việc 3')).toBeInTheDocument()
    const columns = screen.getByRole('group', { name: 'Cột đang xem' })
    expect(within(columns).getByRole('button', { name: 'Đang làm' })).toHaveAttribute('aria-pressed', 'true')

    await user.click(screen.getByRole('button', { name: 'Của tôi' }))
    expect(screen.queryByText('Việc 3')).toBeNull()
    expect(screen.getByText('Việc 1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Của tôi' }))

    await user.selectOptions(screen.getByRole('combobox', { name: 'Giai đoạn' }), 'm2')
    expect(screen.getByText('Việc 2')).toBeInTheDocument()
    expect(screen.queryByText('Việc 1')).toBeNull()
    await user.selectOptions(screen.getByRole('combobox', { name: 'Giai đoạn' }), '')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Loại' }), 'BUG')
    expect(screen.getByText('Việc 4')).toBeInTheDocument()
    expect(screen.queryByText('Việc 1')).toBeNull()
  })

  it('gives the Mentor "Chờ tôi duyệt" and starts phones on "Chờ duyệt"', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('MENTOR'), {
      'GET /api/v1/mentor/jobs/j1': () =>
        json({
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
        }),
      'GET /api/v1/boards/j1/work-items': () => json(board({ mentor: true, viewerId: 'hung' })),
    })
    renderAt('/cong-viec/j1/bang')

    const columns = await screen.findByRole('group', { name: 'Cột đang xem' })
    expect(within(columns).getByRole('button', { name: 'Chờ duyệt' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Chuyển SDB-3 sang…' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Chờ tôi duyệt' }))
    expect(screen.getByText('Việc 4')).toBeInTheDocument()
    expect(screen.queryByText('Việc 1')).toBeNull()
  })
})
