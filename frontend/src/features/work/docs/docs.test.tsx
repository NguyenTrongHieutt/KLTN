import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedIn } from '@/test/fakeBackend'
import { PAYLOADS } from '@/test/payloads'
// Loaded up front: the route's lazy chunk with react-markdown is slow to load the first time.
import './DocsPage'

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

const tree = (overrides: object = {}) => ({
  boardId: 'j1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  mentor: false,
  readOnly: false,
  customer: false,
  templates: [],
  pages: [
    { id: 'p1', title: 'Ghi chú kỹ thuật', versionNo: 1, updatedAt: '2026-10-20T03:00:00Z' },
    { id: 'p2', parentId: 'p1', title: 'Cài đặt', versionNo: 1, updatedAt: '2026-10-20T03:00:00Z' },
    { id: 'p3', title: 'Quyết định', versionNo: 1, updatedAt: '2026-10-20T03:00:00Z' },
  ],
  ...overrides,
})

const page = (overrides: object = {}) => ({
  id: 'p1',
  boardId: 'j1',
  title: 'Ghi chú kỹ thuật',
  body: '# Cách chạy\n\nChạy `npm ci`.',
  versionNo: 1,
  author: 'Trần Thảo Vy',
  at: '2026-10-20T03:00:00Z',
  readOnly: false,
  visibility: 'TEAM',
  mentor: false,
  ...overrides,
})

describe('JOB-3 "Tài liệu" (FR-29, AD-23, UX-DR39)', () => {
  it('shows the tree and the Page, and saves an edit as a new version', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const saved: unknown[] = []
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/pages': () => json(tree()),
      'GET /api/v1/pages/p1': () => json(page()),
      'PUT /api/v1/pages/p1': async (request) => {
        saved.push(await request.json())
        return json(page({ body: '# Cách chạy\n\nChạy `npm install`.', versionNo: 2 }))
      },
    })
    renderAt('/cong-viec/j1/tai-lieu')

    const nav = await screen.findByRole('navigation', { name: 'Các trang tài liệu' })
    expect(within(nav).getAllByRole('link').map((link) => link.textContent)).toEqual(['Ghi chú kỹ thuật', 'Cài đặt', 'Quyết định'])
    expect(await screen.findByRole('heading', { level: 1, name: 'Cách chạy' })).toBeInTheDocument()
    expect(screen.getByText(/Phiên bản 1 · Trần Thảo Vy/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Sửa' }))
    const body = screen.getByRole('textbox', { name: 'Nội dung (Markdown)' })
    await user.clear(body)
    await user.type(body, '# Cách chạy{enter}{enter}Chạy npm install.')
    await user.click(screen.getByRole('tab', { name: 'Xem trước' }))
    expect(screen.getByText('Chạy npm install.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Lưu' }))

    await waitFor(() => expect(saved).toHaveLength(1))
    expect(saved[0]).toMatchObject({ title: 'Ghi chú kỹ thuật', version: 1, sensitive: 'CHECK' })
    expect((await screen.findAllByText('Đã lưu phiên bản 2')).length).toBeGreaterThan(0)
  })

  it('renders Markdown without HTML, images, or links other than https', { timeout: 20_000 }, async () => {
    const markdown = [
      '[Hướng dẫn](https://vibefix.vn/huong-dan)',
      '[Bấm vào](javascript:alert(1))',
      '[Không mã hóa](http://example.test)',
      '![logo](https://evil.test/a.png)',
      '<script>alert(1)</script>',
      ...PAYLOADS,
    ].join('\n\n')
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/pages': () => json(tree()),
      'GET /api/v1/pages/p1': () => json(page({ body: markdown })),
    })
    renderAt('/cong-viec/j1/tai-lieu/p1')

    const link = await screen.findByRole('link', { name: /Hướng dẫn/ })
    expect(link).toHaveAttribute('href', 'https://vibefix.vn/huong-dan')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(within(link).getByText('(vibefix.vn)')).toBeInTheDocument()
    expect(screen.getByText('Bấm vào').closest('a')).toBeNull()
    expect(screen.getByText('Không mã hóa').closest('a')).toBeNull()
    expect(screen.getByText('[Ảnh: logo]')).toBeInTheDocument()
    expect(document.querySelector('main img, main script, main svg[onload]')).toBeNull()
    expect(screen.getAllByText('<script>alert(1)</script>').length).toBeGreaterThan(0)
    for (const payload of PAYLOADS.filter((value) => value.startsWith('<'))) {
      expect((await screen.findAllByText(payload)).length).toBeGreaterThan(0)
    }
  })

  it('opens the Stale dialog when someone saved first', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/pages': () => json(tree()),
      'GET /api/v1/pages/p1': () => json(page()),
      'PUT /api/v1/pages/p1': () => json({ status: 409, code: 'STALE' }, 409),
    })
    renderAt('/cong-viec/j1/tai-lieu/p1')

    await user.click(await screen.findByRole('button', { name: 'Sửa' }))
    await user.type(screen.getByRole('textbox', { name: 'Nội dung (Markdown)' }), ' thêm')
    await user.click(screen.getByRole('button', { name: 'Lưu' }))
    const dialog = await screen.findByRole('dialog', { name: 'Thông tin đã thay đổi trong lúc bạn xem.' })
    await user.click(within(dialog).getByRole('button', { name: 'Xem bản mới nhất' }))
    expect(await screen.findByRole('button', { name: 'Sửa' })).toBeInTheDocument()
  })

  it('asks before leaving with unsaved changes', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/pages': () => json(tree()),
      'GET /api/v1/pages/p1': () => json(page()),
      'GET /api/v1/pages/p3': () => json(page({ id: 'p3', title: 'Quyết định', body: 'Dùng Postgres.' })),
    })
    renderAt('/cong-viec/j1/tai-lieu/p1')

    await user.click(await screen.findByRole('button', { name: 'Sửa' }))
    await user.type(screen.getByRole('textbox', { name: 'Nội dung (Markdown)' }), ' thêm')
    await user.click(screen.getByRole('link', { name: 'Quyết định' }))
    const dialog = await screen.findByRole('dialog', { name: 'Rời trang khi chưa lưu?' })
    await user.click(within(dialog).getByRole('button', { name: 'Ở lại' }))
    expect(screen.getByRole('textbox', { name: 'Nội dung (Markdown)' })).toHaveValue('# Cách chạy\n\nChạy `npm ci`. thêm')

    await user.click(screen.getByRole('link', { name: 'Quyết định' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Rời trang khi chưa lưu?' })).getByRole('button', { name: 'Rời trang' }))
    expect(await screen.findByText('Dùng Postgres.')).toBeInTheDocument()
  })

  it('shows the versions, one read-only, and restores an earlier one', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const restored: unknown[] = []
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/pages': () => json(tree()),
      'GET /api/v1/pages/p1': () => json(page({ versionNo: 2 })),
      'GET /api/v1/pages/p1/versions': () =>
        json([
          { versionNo: 2, title: 'Ghi chú kỹ thuật', author: 'Đỗ Mạnh Hùng', at: '2026-10-20T04:00:00Z' },
          { versionNo: 1, title: 'Ghi chú', author: 'Trần Thảo Vy', at: '2026-10-20T03:00:00Z' },
        ]),
      'GET /api/v1/pages/p1/versions/1': () =>
        json({ versionNo: 1, title: 'Ghi chú', body: 'Bản đầu tiên', author: 'Trần Thảo Vy', at: '2026-10-20T03:00:00Z' }),
      'POST /api/v1/pages/p1/versions/1/restore': async (request) => {
        restored.push(await request.json())
        return json(page({ versionNo: 3, restoredFrom: 1, body: 'Bản đầu tiên' }))
      },
    })
    renderAt('/cong-viec/j1/tai-lieu/p1')

    await user.click(await screen.findByRole('button', { name: 'Lịch sử phiên bản' }))
    const sheet = await screen.findByRole('dialog', { name: 'Lịch sử phiên bản' })
    expect(await within(sheet).findByText('Phiên bản 2: Ghi chú kỹ thuật')).toBeInTheDocument()
    expect(within(sheet).getAllByRole('button', { name: 'Khôi phục' })).toHaveLength(1)
    await user.click(within(sheet).getAllByRole('button', { name: 'Xem' })[1])
    expect(await within(sheet).findByText('Bản đầu tiên')).toBeInTheDocument()
    await user.click(within(sheet).getByRole('button', { name: 'Khôi phục' }))
    await waitFor(() => expect(restored).toEqual([{ version: 2 }]))
    expect((await screen.findAllByText('Đã khôi phục phiên bản 1 thành phiên bản 3')).length).toBeGreaterThan(0)
  })

  it('creates a Page and opens it', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const created: unknown[] = []
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/pages': () => json(tree({ pages: [] })),
      'POST /api/v1/boards/j1/pages': async (request) => {
        created.push(await request.json())
        return json(page({ id: 'p9', title: 'Biên bản họp', body: '' }), 201)
      },
      'GET /api/v1/pages/p9': () => json(page({ id: 'p9', title: 'Biên bản họp', body: '' })),
    })
    renderAt('/cong-viec/j1/tai-lieu')

    expect(await screen.findByText(/Chưa có trang nào/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Trang mới' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Trang trống' }))
    await user.click(screen.getByRole('button', { name: 'Lưu' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Hãy nhập tên trang.')
    await user.type(screen.getByRole('textbox', { name: 'Tên trang' }), 'Biên bản họp')
    await user.click(screen.getByRole('button', { name: 'Lưu' }))
    await waitFor(() => expect(created).toEqual([{ title: 'Biên bản họp', body: '', sensitive: 'CHECK' }]))
    expect(await screen.findByRole('heading', { level: 2, name: 'Biên bản họp' })).toBeInTheDocument()
  })

  it('is read-only for a former member and not found for a Customer', { timeout: 20_000 }, async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/boards/j1/pages': () => json(tree({ readOnly: true })),
      'GET /api/v1/pages/p1': () => json(page({ readOnly: true })),
    })
    renderAt('/cong-viec/j1/tai-lieu')
    expect(await screen.findByRole('heading', { level: 1, name: 'Cách chạy' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Trang mới' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Sửa' })).toBeNull()
  })
})
