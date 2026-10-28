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

afterEach(() => {
  vi.unstubAllGlobals()
})

const card = (overrides: object = {}) => ({
  id: 'w1',
  key: 'SDB-1',
  type: 'STORY',
  title: 'Hai khách không đặt trùng bàn',
  description: '',
  status: 'IN_PROGRESS',
  milestoneId: 'm1',
  seeded: true,
  checklistDone: 0,
  checklistTotal: 0,
  assignee: { accountId: 'vy', name: 'Trần Thảo Vy' },
  version: 3,
  ...overrides,
})

const board = (overrides: object = {}) => ({
  boardId: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  code: 'SDB',
  viewerId: 'vy',
  mentor: false,
  readOnly: false,
  milestones: [{ milestoneId: 'm1', number: 1, title: 'Sửa lỗi trùng giờ', held: true }],
  crew: [],
  workload: [],
  items: [card()],
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

describe('The Review Gate on JOB-2 (FR-25, AD-39, UX-DR38)', () => {
  it('lets the assignee submit only with all four Self-check items', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/boards/j1/work-items': () => json(board()),
      'GET /api/v1/work-items/w1': () =>
        json({ item: card(), checklist: [], createdAt: '2026-10-20T03:00:00Z', readOnly: false, attachments: [], history: [] }),
      'POST /api/v1/work-items/w1/submit': async (request) => {
        sent.push(await request.json())
        return json({ item: card({ status: 'IN_REVIEW', version: 4 }), checklist: [], createdAt: '2026-10-20T03:00:00Z', readOnly: false, attachments: [], history: [] })
      },
    })
    renderAt('/cong-viec/j1/bang')

    await user.click(await screen.findByRole('button', { name: 'Chuyển SDB-1 sang…' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Gửi duyệt' }))
    const dialog = await screen.findByRole('dialog', { name: 'Gửi duyệt SDB-1' })
    await user.click(within(dialog).getByRole('button', { name: 'Gửi duyệt' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Hãy đánh dấu đủ bốn mục tự kiểm trước khi gửi duyệt.')
    expect(sent).toHaveLength(0)

    for (const name of ['Đạt tiêu chí', 'Đã kiểm thử', 'Đã cập nhật tài liệu', 'Không lưu khóa bí mật trong mã']) {
      await user.click(within(dialog).getByRole('checkbox', { name }))
    }
    await user.type(within(dialog).getByRole('textbox', { name: /Ghi chú cho người hướng dẫn/ }), 'Em đã thêm kiểm thử')
    await user.click(within(dialog).getByRole('button', { name: 'Gửi duyệt' }))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toEqual({
      version: 3,
      selfCheck: { meetsCriteria: true, tested: true, docsUpdated: true, noSecrets: true },
      note: 'Em đã thêm kiểm thử',
      sensitive: 'CHECK',
    })
    expect((await screen.findAllByText('Đã gửi duyệt SDB-1')).length).toBeGreaterThan(0)
  })

  it('shows the Mentor the answers and needs a comment to return or approve', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/work-items': () => json(board({ mentor: true, viewerId: 'hung', items: [card({ status: 'IN_REVIEW' })] })),
      'GET /api/v1/work-items/w1': () =>
        json({
          item: card({ status: 'IN_REVIEW' }),
          checklist: [],
          createdAt: '2026-10-20T03:00:00Z',
          readOnly: false,
          review: {
            action: 'SUBMITTED',
            by: 'Trần Thảo Vy',
            comment: 'Em đã thêm kiểm thử',
            selfCheck: { meetsCriteria: true, tested: true, docsUpdated: true, noSecrets: true },
            at: '2026-10-20T04:00:00Z',
          },
          attachments: [],
          history: [],
        }),
      'POST /api/v1/work-items/w1/review': async (request) => {
        sent.push(await request.json())
        return json({ item: card({ status: 'IN_PROGRESS', returnedReason: 'Thêm kiểm thử xóa đơn', version: 4 }), checklist: [], createdAt: '2026-10-20T03:00:00Z', readOnly: false, attachments: [], history: [] })
      },
    })
    renderAt('/cong-viec/j1/bang')

    await user.click(await screen.findByRole('button', { name: 'Chuyển SDB-1 sang…' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Trả lại' }))
    const dialog = await screen.findByRole('dialog', { name: 'Duyệt SDB-1' })
    expect(await within(dialog).findByText(/Trần Thảo Vy tự kiểm lúc/)).toBeInTheDocument()
    expect(within(dialog).getByText('Ghi chú: Em đã thêm kiểm thử')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Duyệt' }))
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Hãy viết nhận xét trước khi duyệt hoặc trả lại.')

    await user.type(within(dialog).getByRole('textbox', { name: 'Nhận xét của bạn' }), 'Thêm kiểm thử xóa đơn')
    await user.click(within(dialog).getByRole('button', { name: 'Trả lại' }))
    await waitFor(() => expect(sent).toEqual([{ version: 3, decision: 'RETURN', comment: 'Thêm kiểm thử xóa đơn', sensitive: 'CHECK' }]))
    expect((await screen.findAllByText('Đã trả lại SDB-1')).length).toBeGreaterThan(0)
  })

  it('shows a refusal inline in the Review dialog', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/boards/j1/work-items': () => json(board({ mentor: true, viewerId: 'hung', items: [card({ status: 'IN_REVIEW' })] })),
      'GET /api/v1/work-items/w1': () =>
        json({ item: card({ status: 'IN_REVIEW' }), checklist: [], createdAt: '2026-10-20T03:00:00Z', readOnly: false, attachments: [], history: [] }),
      'POST /api/v1/work-items/w1/review': () => json({ status: 409, code: 'STALE' }, 409),
    })
    renderAt('/cong-viec/j1/bang')

    await user.click(await screen.findByRole('button', { name: 'Chuyển SDB-1 sang…' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Duyệt' }))
    const dialog = await screen.findByRole('dialog', { name: 'Duyệt SDB-1' })
    await user.type(within(dialog).getByRole('textbox', { name: 'Nhận xét của bạn' }), 'Đạt')
    await user.click(within(dialog).getByRole('button', { name: 'Duyệt' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Thông tin đã thay đổi trong lúc bạn xem.')
  })

  it('marks a returned card and tells the assignee their submission waits', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/boards/j1/work-items': () =>
        json(
          board({
            items: [
              card({ returnedReason: 'Hãy thêm kiểm thử cho trường hợp xóa đơn' }),
              card({ id: 'w2', key: 'SDB-2', title: 'Email nhắc lịch', status: 'IN_REVIEW', seeded: false }),
            ],
          }),
        ),
    })
    renderAt('/cong-viec/j1/bang')

    expect(await screen.findByText('Bị trả lại')).toBeInTheDocument()
    expect(screen.getByText('Hãy thêm kiểm thử cho trường hợp xóa đơn')).toBeInTheDocument()
    expect(screen.getByText('Chờ người hướng dẫn duyệt')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Chuyển SDB-2 sang…' })).toBeNull()
  })
})
