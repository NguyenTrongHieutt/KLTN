import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob } from '@/test/fakeBackend'
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

afterEach(() => {
  vi.unstubAllGlobals()
})

const card = (overrides: object = {}) => ({
  id: 'w3',
  key: 'SDB-3',
  type: 'TASK',
  title: 'Viết kiểm thử trùng giờ',
  description: 'Hai khách cùng giờ.',
  status: 'TODO',
  milestoneId: 'm1',
  seeded: false,
  priority: 'HIGH',
  estimateHours: 2.5,
  checklistDone: 1,
  checklistTotal: 2,
  assignee: { accountId: 's1', name: 'Trần Thảo Vy' },
  version: 2,
  ...overrides,
})

const board = (overrides: object = {}) => ({
  boardId: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  code: 'SDB',
  mentor: true,
  readOnly: false,
  milestones: [{ milestoneId: 'm1', number: 1, title: 'Sửa lỗi trùng giờ' }],
  crew: [
    { accountId: 's1', name: 'Trần Thảo Vy', available: true },
    { accountId: 's2', name: 'Lê Minh Tùng', available: true },
  ],
  workload: [],
  items: [card()],
  ...overrides,
})

const detail = (overrides: object = {}) => ({
  item: card(),
  checklist: [
    { text: 'Có kiểm thử', done: true },
    { text: 'Chạy trên điện thoại', done: false },
  ],
  createdBy: 'Trần Thảo Vy',
  createdAt: '2026-10-20T03:00:00Z',
  readOnly: false,
  attachments: [],
  history: [
    { id: 'h1', actor: 'Trần Thảo Vy', action: 'CREATED', fields: ['type', 'title'], at: '2026-10-20T03:00:00Z' },
    {
      id: 'h2',
      actor: 'Đỗ Mạnh Hùng',
      action: 'UPDATED',
      fields: ['assigneeId', 'priority'],
      assignedTo: 'Trần Thảo Vy',
      at: '2026-10-20T04:00:00Z',
    },
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

describe('Work Items on JOB-2 (FR-23, AD-23, AD-39, UX-DR36)', () => {
  it('shows the card fields and lets the Mentor edit and assign in the detail', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const saved: unknown[] = []
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/work-items': () => json(board()),
      'GET /api/v1/work-items/w3': () => json(detail()),
      'PUT /api/v1/work-items/w3': async (request) => {
        saved.push(await request.json())
        return json(detail({ item: card({ title: 'Kiểm thử trùng giờ', assignee: { accountId: 's2', name: 'Lê Minh Tùng' }, version: 3 }) }))
      },
    })
    renderAt('/cong-viec/j1/bang')

    const todo = await screen.findByRole('region', { name: /Cần làm/ })
    expect(within(todo).getByText('Cao')).toBeInTheDocument()
    expect(within(todo).getByText('2,5 giờ')).toBeInTheDocument()
    expect(within(todo).getByText('1/2 tiêu chí')).toBeInTheDocument()
    expect(within(todo).getByText('Người làm: Trần Thảo Vy')).toBeInTheDocument()

    await user.click(within(todo).getByRole('button', { name: 'Viết kiểm thử trùng giờ' }))
    const sheet = await screen.findByRole('dialog', { name: 'SDB-3' })
    expect(within(sheet).getByText('Trần Thảo Vy tạo việc')).toBeInTheDocument()
    expect(within(sheet).getByText('Đỗ Mạnh Hùng giao cho Trần Thảo Vy')).toBeInTheDocument()
    const title = within(sheet).getByRole('textbox', { name: 'Tiêu đề' })
    await user.clear(title)
    await user.type(title, 'Kiểm thử trùng giờ')
    await user.selectOptions(within(sheet).getByRole('combobox', { name: 'Người làm' }), 's2')
    await user.click(within(sheet).getByRole('button', { name: 'Lưu' }))

    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0]).toMatchObject({
      type: 'TASK',
      title: 'Kiểm thử trùng giờ',
      milestoneId: 'm1',
      priority: 'HIGH',
      estimateHours: 2.5,
      assigneeId: 's2',
      version: 2,
      sensitive: 'CHECK',
      checklist: [
        { text: 'Có kiểm thử', done: true },
        { text: 'Chạy trên điện thoại', done: false },
      ],
    })
    expect((await screen.findAllByText('Đã lưu SDB-3')).length).toBeGreaterThan(0)
  })

  it('lets a Crew member add a Work Item, with the assignee and priority left to the Mentor', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/boards/j1/work-items': () => json(board({ mentor: false, crew: [], items: [] })),
      'POST /api/v1/boards/j1/work-items': async (request) => {
        const body = (await request.json()) as { sensitive: string }
        sent.push(body)
        if (body.sensitive === 'CHECK') {
          return json(
            {
              status: 422,
              code: 'GUARD_FAILED',
              reason: 'SENSITIVE_CONTENT',
              matches: [{ kind: 'CREDENTIAL', type: 'Khóa GitHub', masked: 'ghp_••••••••Q7mZ' }],
            },
            422,
          )
        }
        return json(detail({ item: card({ key: 'SDB-4', priority: undefined, assignee: undefined }) }), 201)
      },
    })
    renderAt('/cong-viec/j1/bang')

    await user.click(await screen.findByRole('button', { name: 'Thêm việc' }))
    const sheet = await screen.findByRole('dialog', { name: 'Thêm việc' })
    expect(within(sheet).getByRole('combobox', { name: 'Ưu tiên' })).toBeDisabled()
    expect(within(sheet).getByRole('combobox', { name: 'Người làm' })).toBeDisabled()
    expect(within(sheet).getAllByText('Chỉ người hướng dẫn đặt được.')).toHaveLength(2)
    await user.click(within(sheet).getByRole('button', { name: 'Lưu' }))
    expect(within(sheet).getByText('Hãy nhập tiêu đề.')).toBeInTheDocument()
    await user.type(within(sheet).getByRole('textbox', { name: 'Tiêu đề' }), 'Khóa thử')
    await user.click(within(sheet).getByRole('button', { name: 'Lưu' }))

    const warning = await screen.findByRole('dialog', { name: /có thể chứa khóa bí mật/ })
    expect(within(warning).getByText('ghp_••••••••Q7mZ')).toBeInTheDocument()
    expect(within(warning).queryByRole('button', { name: 'Vẫn gửi' })).toBeNull()
    await user.click(within(warning).getByRole('button', { name: 'Che và lưu' }))

    await waitFor(() => expect(sent).toHaveLength(2))
    expect(sent[1]).toMatchObject({ title: 'Khóa thử', sensitive: 'MASK', type: 'TASK', milestoneId: 'm1' })
    expect(sent[0]).not.toHaveProperty('assigneeId')
    expect((await screen.findAllByText('Đã lưu SDB-4')).length).toBeGreaterThan(0)
  })

  it('opens the Stale dialog when someone saved first', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/work-items': () => json(board()),
      'GET /api/v1/work-items/w3': () => json(detail()),
      'PUT /api/v1/work-items/w3': () => json({ status: 409, code: 'STALE' }, 409),
    })
    renderAt('/cong-viec/j1/bang')

    await user.click(await screen.findByRole('button', { name: 'Viết kiểm thử trùng giờ' }))
    const sheet = await screen.findByRole('dialog', { name: 'SDB-3' })
    await user.click(within(sheet).getByRole('button', { name: 'Lưu' }))

    const stale = await screen.findByRole('dialog', { name: 'Thông tin đã thay đổi trong lúc bạn xem.' })
    expect(within(stale).getByText(/thay đổi của bạn chưa được lưu/)).toBeInTheDocument()
  })

  it('shows a former member a read-only Board and every field as text', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/boards/j1/work-items': () =>
        json(
          board({
            mentor: false,
            readOnly: true,
            crew: [],
            items: PAYLOADS.map((payload, index) =>
              card({ id: `p${index}`, key: `SDB-${index + 1}`, title: payload, assignee: { accountId: 's1', name: payload } }),
            ),
          }),
        ),
      'GET /api/v1/work-items/p0': () =>
        json(detail({ readOnly: true, item: card({ id: 'p0', title: PAYLOADS[0] }), createdBy: PAYLOADS[1] })),
    })
    renderAt('/cong-viec/j1/bang')

    for (const payload of PAYLOADS) {
      expect((await screen.findAllByText(payload)).length).toBeGreaterThan(0)
    }
    expect(document.querySelector('img[src="x"], script, svg[onload]')).toBeNull()
    expect(screen.getByText('Bảng này chỉ để xem.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Thêm việc' })).toBeNull()

    await user.click(screen.getByRole('button', { name: PAYLOADS[0] }))
    const sheet = await screen.findByRole('dialog', { name: 'SDB-3' })
    expect(within(sheet).queryByRole('button', { name: 'Lưu' })).toBeNull()
    expect(within(sheet).getByRole('textbox', { name: 'Tiêu đề' })).toHaveAttribute('readonly')
  })
})
