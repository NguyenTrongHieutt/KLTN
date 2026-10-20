import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'
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
  vi.restoreAllMocks()
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

const file = (overrides: object = {}) => ({
  id: 'a1',
  kind: 'FILE',
  name: '0007_delete_order_policy.sql',
  downloadUrl: '/api/v1/files/f1',
  mediaType: 'text/plain',
  size: 2048,
  author: 'Trần Thảo Vy',
  removable: true,
  addedAt: '2026-10-19T09:10:00Z',
  ...overrides,
})

const link = (overrides: object = {}) => ({
  id: 'a2',
  kind: 'LINK',
  name: 'Pull request sửa trùng giờ',
  domain: 'github.com',
  url: 'https://github.com/lan/so-dat-ban/pull/3',
  author: 'Đỗ Mạnh Hùng',
  removable: false,
  addedAt: '2026-10-19T10:00:00Z',
  ...overrides,
})

const history = [
  { id: 'h1', actor: '', mine: false, action: 'SEEDED', fields: [], at: '2026-10-18T03:00:00Z' },
  {
    id: 'n1',
    actor: 'Trần Thảo Vy',
    mine: true,
    action: 'NOTE',
    fields: [],
    comment: 'Đã thêm quy tắc trong tệp cập nhật cơ sở dữ liệu.',
    at: '2026-10-19T09:05:00Z',
  },
  {
    id: 'h2',
    actor: 'Trần Thảo Vy',
    mine: true,
    action: 'ATTACHED',
    fields: [],
    attachment: '0007_delete_order_policy.sql',
    at: '2026-10-19T09:10:00Z',
  },
  {
    id: 'h3',
    actor: 'Trần Thảo Vy',
    mine: true,
    action: 'DETACHED',
    fields: [],
    attachment: 'ban-nhap.txt',
    at: '2026-10-19T09:12:00Z',
  },
  { id: 'h4', actor: 'Đỗ Mạnh Hùng', mine: false, action: 'RETURNED', fields: [], comment: 'Hãy thêm kiểm thử', at: '2026-10-19T10:05:00Z' },
  {
    id: 'n2',
    actor: 'Trần Thảo Vy',
    mine: true,
    action: 'NOTE',
    fields: [],
    comment: 'Sửa: quy tắc nằm trong tệp 0007, kiểm thử đang viết.',
    quoted: { id: 'n1', actor: 'Trần Thảo Vy', text: 'Đã thêm quy tắc trong tệp cập nhật cơ sở dữ liệu.', at: '2026-10-19T09:05:00Z' },
    at: '2026-10-20T01:50:00Z',
  },
]

const detail = (overrides: object = {}) => ({
  item: card(),
  checklist: [],
  createdAt: '2026-10-18T03:00:00Z',
  readOnly: false,
  attachments: [file(), link()],
  history,
  ...overrides,
})

async function openSheet(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Hai khách không đặt trùng bàn' }))
  return screen.findByRole('dialog', { name: 'SDB-1' })
}

describe('Attachments and notes on JOB-2 (FR-76, AD-39, UX-DR79)', () => {
  it('lists files and links with author and time, and "Gỡ" only on the viewer’s own', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/work-items': () => json(board()),
      'GET /api/v1/work-items/w1': () => json(detail()),
      'DELETE /api/v1/work-items/w1/attachments/a1': () => json(detail({ attachments: [link()] })),
    })
    renderAt('/cong-viec/j1/bang')
    const sheet = await openSheet(user)

    const list = within(sheet).getByRole('heading', { name: 'Tệp đính kèm' }).closest('section')!
    const sql = within(list).getByRole('link', { name: '0007_delete_order_policy.sql' })
    expect(sql).toHaveAttribute('href', '/api/v1/files/f1')
    expect(within(list).getByText(/2 KB · Trần Thảo Vy ·/)).toBeInTheDocument()
    const pr = within(list).getByRole('link', { name: /Pull request sửa trùng giờ/ })
    expect(pr).toHaveAttribute('href', 'https://github.com/lan/so-dat-ban/pull/3')
    expect(pr).toHaveAttribute('target', '_blank')
    expect(pr).toHaveAttribute('rel', 'noopener noreferrer nofollow')
    expect(within(list).getByText(/github\.com · Đỗ Mạnh Hùng ·/)).toBeInTheDocument()
    expect(within(list).getAllByRole('button', { name: /^Gỡ / })).toHaveLength(1)

    await user.click(within(list).getByRole('button', { name: 'Gỡ 0007_delete_order_policy.sql' }))
    await waitFor(() => expect(within(list).queryByRole('link', { name: '0007_delete_order_policy.sql' })).toBeNull())
  })

  it('adds an https link and a file, refusing SVG on the client and masking a text file after FR-47', { timeout: 20_000 }, async () => {
    const user = userEvent.setup({ applyAccept: false })
    const links: unknown[] = []
    let uploads = 0
    // jsdom's FormData does not survive a Request, so the choice is read where the form is built.
    const appended = vi.spyOn(FormData.prototype, 'append')
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/work-items': () => json(board()),
      'GET /api/v1/work-items/w1': () => json(detail({ attachments: [] })),
      'POST /api/v1/work-items/w1/links': async (request) => {
        links.push(await request.json())
        return json(detail({ attachments: [link({ name: 'docs.google.com', domain: 'docs.google.com', url: 'https://docs.google.com/d/1' })] }), 201)
      },
      'POST /api/v1/work-items/w1/attachments': () => {
        uploads += 1
        if (uploads === 1) {
          return json(
            {
              status: 422,
              code: 'GUARD_FAILED',
              reason: 'SENSITIVE_CONTENT',
              matches: [{ kind: 'CREDENTIAL', type: 'GitHub token', masked: 'ghp_••••••' }],
            },
            422,
          )
        }
        return json(detail({ attachments: [file({ name: 'cau-hinh.txt' })] }), 201)
      },
    })
    renderAt('/cong-viec/j1/bang')
    const sheet = await openSheet(user)
    expect(within(sheet).getByText('Chưa có tệp hoặc liên kết nào.')).toBeInTheDocument()

    await user.click(within(sheet).getByRole('button', { name: 'Thêm tệp hoặc liên kết' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Thêm liên kết' }))
    await user.type(within(sheet).getByRole('textbox', { name: 'Liên kết (https://)' }), 'http://docs.google.com/d/1')
    await user.click(within(sheet).getByRole('button', { name: 'Thêm liên kết' }))
    expect(within(sheet).getByRole('alert')).toHaveTextContent('Liên kết phải bắt đầu bằng https://')
    expect(links).toHaveLength(0)
    const url = within(sheet).getByRole('textbox', { name: 'Liên kết (https://)' })
    await user.clear(url)
    await user.type(url, 'https://docs.google.com/d/1')
    await user.click(within(sheet).getByRole('button', { name: 'Thêm liên kết' }))
    await waitFor(() => expect(links).toEqual([{ url: 'https://docs.google.com/d/1', label: '', sensitive: 'CHECK' }]))
    expect(await within(sheet).findByRole('link', { name: /docs\.google\.com/ })).toBeInTheDocument()

    const input = sheet.querySelector('input[type="file"]') as HTMLInputElement
    await user.upload(input, new File(['<svg/>'], 'hinh.svg', { type: 'image/svg+xml' }))
    expect(within(sheet).getByRole('alert')).toHaveTextContent('Không nhận SVG hoặc HTML')
    expect(uploads).toBe(0)

    await user.upload(input, new File(['GITHUB=ghp'], 'cau-hinh.txt', { type: 'text/plain' }))
    const warning = await screen.findByRole('dialog', { name: 'Nội dung có thể chứa khóa bí mật hoặc dữ liệu khách hàng' })
    expect(within(warning).getByText('ghp_••••••')).toBeInTheDocument()
    await user.click(within(warning).getByRole('button', { name: 'Che và lưu' }))
    await waitFor(() => expect(uploads).toBe(2))
    const choices = appended.mock.calls.filter(([name]) => name === 'sensitive').map(([, value]) => value)
    expect(choices).toEqual(['CHECK', 'MASK'])
    expect(await within(sheet).findByRole('link', { name: 'cau-hinh.txt' })).toBeInTheDocument()
  })

  it('keeps one time-ordered history and corrects a note by quoting it, never editing it', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const notes: unknown[] = []
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/work-items': () => json(board()),
      'GET /api/v1/work-items/w1': () => json(detail()),
      'POST /api/v1/work-items/w1/notes': async (request) => {
        notes.push(await request.json())
        return json(detail(), 201)
      },
    })
    renderAt('/cong-viec/j1/bang')
    const sheet = await openSheet(user)

    const thread = within(sheet).getByRole('heading', { name: 'Ghi chú và lịch sử' }).closest('section')!
    const lines = within(thread).getAllByRole('listitem').map((line) => line.textContent ?? '')
    expect(lines[0]).toContain('vibefix tạo từ tiêu chí nghiệm thu')
    expect(lines[1]).toContain('Đã thêm quy tắc trong tệp cập nhật cơ sở dữ liệu.')
    expect(lines[2]).toContain('Trần Thảo Vy đã thêm 0007_delete_order_policy.sql')
    expect(lines[3]).toContain('Trần Thảo Vy đã gỡ tệp ban-nhap.txt')
    expect(lines[4]).toContain('Đỗ Mạnh Hùng trả lại')
    expect(lines[5]).toContain('ghi chú sửa lại')
    expect(lines[5]).toContain('Sửa: quy tắc nằm trong tệp 0007, kiểm thử đang viết.')
    expect(within(thread).getByText('Ghi chú không sửa hoặc xóa được sau khi lưu.')).toBeInTheDocument()
    expect(within(thread).queryByRole('button', { name: /Xóa/ })).toBeNull()

    await user.click(within(thread).getAllByRole('button', { name: /^Sửa lại ghi chú lúc/ })[0])
    expect(within(thread).getByText(/Ghi chú sửa lại cho ghi chú lúc/)).toBeInTheDocument()
    const box = within(thread).getByRole('textbox', { name: 'Ghi chú mới' })
    expect(box).toHaveValue('Đã thêm quy tắc trong tệp cập nhật cơ sở dữ liệu.')
    await user.clear(box)
    await user.type(box, 'Quy tắc nằm trong tệp 0007.')
    await user.click(within(thread).getByRole('button', { name: 'Lưu ghi chú' }))
    await waitFor(() => expect(notes).toEqual([{ text: 'Quy tắc nằm trong tệp 0007.', quotes: 'n1', sensitive: 'CHECK' }]))
  })

  it('shows the Mentor the Attachments and the latest notes in the Review dialog, read-only', { timeout: 20_000 }, async () => {
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
      'GET /api/v1/boards/j1/work-items': () =>
        json(board({ mentor: true, viewerId: 'hung', items: [card({ status: 'IN_REVIEW' })] })),
      'GET /api/v1/work-items/w1': () =>
        json(
          detail({
            item: card({ status: 'IN_REVIEW' }),
            attachments: [file({ removable: false }), link({ removable: true })],
            review: {
              action: 'SUBMITTED',
              by: 'Trần Thảo Vy',
              selfCheck: { meetsCriteria: true, tested: true, docsUpdated: true, noSecrets: true },
              at: '2026-10-20T02:40:00Z',
            },
          }),
        ),
    })
    renderAt('/cong-viec/j1/bang')

    await user.click(await screen.findByRole('button', { name: 'Chuyển SDB-1 sang…' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Duyệt' }))
    const dialog = await screen.findByRole('dialog', { name: 'Duyệt SDB-1' })
    expect(await within(dialog).findByRole('link', { name: '0007_delete_order_policy.sql' })).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: /Pull request sửa trùng giờ/ })).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: /^Gỡ / })).toBeNull()
    expect(within(dialog).queryByRole('button', { name: 'Thêm tệp hoặc liên kết' })).toBeNull()
    const latest = within(dialog).getByRole('heading', { name: 'Ghi chú mới nhất' }).closest('section')!
    const shown = within(latest).getAllByRole('listitem')
    expect(shown[0]).toHaveTextContent('Sửa: quy tắc nằm trong tệp 0007, kiểm thử đang viết.')
    expect(shown[1]).toHaveTextContent('Đã thêm quy tắc trong tệp cập nhật cơ sở dữ liệu.')
  })

  it('offers nothing to write on a read-only Board', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/work-items': () => json(board({ readOnly: true })),
      'GET /api/v1/work-items/w1': () => json(detail({ readOnly: true, attachments: [file({ removable: false })] })),
    })
    renderAt('/cong-viec/j1/bang')
    const sheet = await openSheet(user)
    expect(within(sheet).getByRole('link', { name: '0007_delete_order_policy.sql' })).toBeInTheDocument()
    expect(within(sheet).queryByRole('button', { name: 'Thêm tệp hoặc liên kết' })).toBeNull()
    expect(within(sheet).queryByRole('textbox', { name: 'Ghi chú mới' })).toBeNull()
    expect(within(sheet).queryByRole('button', { name: /^Sửa lại ghi chú/ })).toBeNull()
  })

  it('keeps planted payloads in notes and link labels as text (SG-30)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/work-items': () => json(board()),
      'GET /api/v1/work-items/w1': () =>
        json(
          detail({
            attachments: PAYLOADS.map((payload, index) =>
              link({ id: `l${index}`, name: payload, url: index === 3 ? payload : `https://example.test/${index}` }),
            ),
            history: PAYLOADS.map((payload, index) => ({
              id: `n${index}`,
              actor: 'Trần Thảo Vy',
              mine: true,
              action: 'NOTE',
              fields: [],
              comment: payload,
              quoted: index === 0 ? { id: 'q', actor: 'Trần Thảo Vy', text: PAYLOADS[1], at: '2026-10-19T09:05:00Z' } : undefined,
              at: '2026-10-19T09:05:00Z',
            })),
          }),
        ),
    })
    renderAt('/cong-viec/j1/bang')
    const sheet = await openSheet(user)

    for (const payload of PAYLOADS) {
      expect(within(sheet).getAllByText(payload).length).toBeGreaterThan(0)
    }
    expect(document.querySelector('img[src="x"], script, svg[onload]')).toBeNull()
    for (const anchor of Array.from(sheet.querySelectorAll('a'))) {
      expect(anchor.getAttribute('href') ?? '').not.toMatch(/^javascript:/i)
    }
  })
})
