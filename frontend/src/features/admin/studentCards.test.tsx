import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import type { Session } from '@/features/identity/api'
import { fakeBackend, json } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const minutes = (n: number) => new Date(Date.now() + n * 60_000).toISOString()

const administrator: Session = {
  status: 'SIGNED_IN',
  privacyNoticeVersion: '2.0',
  account: { id: '00000000-0000-7000-8000-00000000a001', role: 'ADMINISTRATOR', theme: 'LIGHT' },
  administrator: {
    email: 'quan@vibefix.vn',
    displayName: 'Vũ Minh Quân',
    idleExpiresAt: minutes(30),
    absoluteExpiresAt: minutes(480),
  },
}

const khoa = {
  id: 'c1',
  githubLogin: 'khoa-dev',
  realName: 'Đặng Đăng Khoa',
  profileSchool: 'Đại học Bách khoa Hà Nội',
  status: 'PENDING',
  uploadedAt: '2026-10-19T02:41:00Z',
  fileName: 'the.jpg',
  mediaType: 'image/jpeg',
  fileUrl: '/api/v1/files/f1',
}

const lan = { ...khoa, id: 'c2', githubLogin: 'lan-dev', realName: 'Lê Lan', fileUrl: '/api/v1/files/f2', mediaType: 'application/pdf' }

const page = (items: object[], total = items.length) => json({ items, page: 0, size: 20, total })

const overview = () =>
  json({
    failedOperations: { count: 0 },
    stalledEvents: { count: 0 },
    activeAdministrators: 2,
    studentCards: { count: 2, oldestAt: '2026-10-19T02:41:00Z' },
  })

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ADM-3 student cards (FR-5, UX-DR46)', () => {
  it('lists pending cards from the server, page and sort in the URL, with labelled row actions', async () => {
    const seen = fakeBackend(administrator, {
      'GET /api/v1/admin/overview': overview,
      'GET /api/v1/admin/student-cards': () => page([khoa, lan], 45),
    })
    renderAt('/quan-tri/the-sinh-vien')

    const table = await screen.findByRole('table', { name: 'Danh sách thẻ sinh viên' })
    expect(within(table).getByText('Đặng Đăng Khoa')).toBeInTheDocument()
    expect(within(table).getByText('@khoa-dev')).toBeInTheDocument()
    expect(within(table).getByRole('button', { name: 'Thao tác cho thẻ của Đặng Đăng Khoa' })).toBeInTheDocument()
    expect(screen.getByText('Trang 1/3')).toBeInTheDocument()
    const request = new URL(seen.find((r) => r.url.includes('/api/v1/admin/student-cards'))!.url)
    expect(request.searchParams.get('status')).toBe('PENDING')
    expect(request.searchParams.get('size')).toBe('20')
    expect(request.searchParams.get('sort')).toBe('uploadedAt,asc')
    const nav = screen.getAllByRole('navigation', { name: 'Quản trị' })[0]
    expect(within(nav).getByRole('link', { name: /Thẻ sinh viên/ })).toHaveTextContent('2 đang chờ')
  })

  it('moves through pages and status chips by the URL', async () => {
    const user = userEvent.setup()
    const seen = fakeBackend(administrator, {
      'GET /api/v1/admin/overview': overview,
      'GET /api/v1/admin/student-cards': () => page([khoa], 45),
    })
    const router = renderAt('/quan-tri/the-sinh-vien')
    await screen.findByRole('table', { name: 'Danh sách thẻ sinh viên' })

    await user.click(screen.getByRole('button', { name: 'Trang sau' }))
    await waitFor(() => expect(router.state.location.search).toContain('page=2'))
    await user.click(screen.getByRole('button', { name: 'Bị từ chối' }))
    await waitFor(() => expect(router.state.location.search).toContain('status=REJECTED'))
    expect(router.state.location.search).toContain('sort=decidedAt%2Cdesc')
    expect(router.state.location.search).toContain('page=1')
    await waitFor(() =>
      expect(seen.some((r) => new URL(r.url).searchParams.get('status') === 'REJECTED')).toBe(true),
    )
  })

  // Two dialogs and typing: slower than the default 5 s in jsdom.
  it('approves a card with its student number and the profile school', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let body: unknown
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': overview,
      'GET /api/v1/admin/student-cards': () => page([khoa]),
      'POST /api/v1/admin/student-cards/c1/approve': async (request) => {
        body = await request.json()
        return json({ ...khoa, status: 'APPROVED', studentNumber: '20201234', school: khoa.profileSchool })
      },
    })
    renderAt('/quan-tri/the-sinh-vien')

    // The table and the cards below 768 px both render in jsdom, which applies no media queries.
    await user.click(within(await screen.findByRole('table')).getByRole('button', { name: 'Thao tác cho thẻ của Đặng Đăng Khoa' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Xem thẻ' }))
    const dialog = await screen.findByRole('dialog', { name: 'Thẻ sinh viên của Đặng Đăng Khoa' })
    expect(within(dialog).getByRole('img', { name: 'Ảnh thẻ sinh viên của Đặng Đăng Khoa' })).toHaveAttribute(
      'src',
      '/api/v1/files/f1',
    )
    expect(within(dialog).getByText('Lần mở này được ghi vào nhật ký hệ thống.')).toBeInTheDocument()
    expect(within(dialog).getByRole('textbox', { name: 'Trường' })).toHaveValue('Đại học Bách khoa Hà Nội')

    await user.click(within(dialog).getByRole('button', { name: 'Duyệt thẻ' }))
    expect(within(dialog).getByText('Hãy điền mục này.')).toBeInTheDocument()
    await user.type(within(dialog).getByRole('textbox', { name: 'Mã số sinh viên' }), '20201234')
    await user.click(within(dialog).getByRole('button', { name: 'Duyệt thẻ' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(body).toEqual({ studentNumber: '20201234', school: 'Đại học Bách khoa Hà Nội' })
  })

  it('says when the card already verified another account', async () => {
    const user = userEvent.setup()
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': overview,
      'GET /api/v1/admin/student-cards': () => page([khoa]),
      'POST /api/v1/admin/student-cards/c1/approve': () =>
        new Response(JSON.stringify({ status: 409, code: 'CONFLICT', reason: 'ALREADY_USED_FOR_VERIFICATION' }), {
          status: 409,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
    })
    renderAt('/quan-tri/the-sinh-vien')

    // The table and the cards below 768 px both render in jsdom, which applies no media queries.
    await user.click(within(await screen.findByRole('table')).getByRole('button', { name: 'Thao tác cho thẻ của Đặng Đăng Khoa' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Duyệt' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByRole('textbox', { name: 'Mã số sinh viên' }), '20201234')
    await user.click(within(dialog).getByRole('button', { name: 'Duyệt thẻ' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Email hoặc thẻ sinh viên này đã xác minh cho tài khoản khác.',
    )
  })

  it('rejects with a preset reason or one of its own, after saying what happens', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let body: unknown
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': overview,
      'GET /api/v1/admin/student-cards': () => page([lan]),
      'POST /api/v1/admin/student-cards/c2/reject': async (request) => {
        body = await request.json()
        return json({ ...lan, status: 'REJECTED', rejectionReason: 'Thẻ của trường khác tên' })
      },
    })
    renderAt('/quan-tri/the-sinh-vien')

    // The table and the cards below 768 px both render in jsdom, which applies no media queries.
    await user.click(within(await screen.findByRole('table')).getByRole('button', { name: 'Thao tác cho thẻ của Lê Lan' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Từ chối' }))
    const dialog = await screen.findByRole('dialog', { name: 'Từ chối thẻ của Lê Lan?' })
    expect(within(dialog).getByText('Lê Lan nhận email kèm lý do và có thể tải lên thẻ khác ngay.')).toBeInTheDocument()
    expect(within(dialog).getByRole('radio', { name: 'Ảnh mờ, không đọc được' })).toBeChecked()

    await user.click(within(dialog).getByRole('radio', { name: 'Khác' }))
    await user.click(within(dialog).getByRole('button', { name: 'Từ chối' }))
    expect(within(dialog).getByText('Hãy điền mục này.')).toBeInTheDocument()
    await user.type(within(dialog).getByRole('textbox', { name: 'Lý do khác' }), 'Thẻ của trường khác tên')
    await user.click(within(dialog).getByRole('button', { name: 'Từ chối' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(body).toEqual({ reason: 'Thẻ của trường khác tên' })
  })

  it('offers a PDF card as a download', async () => {
    const user = userEvent.setup()
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': overview,
      'GET /api/v1/admin/student-cards': () => page([lan]),
    })
    renderAt('/quan-tri/the-sinh-vien')

    // The table and the cards below 768 px both render in jsdom, which applies no media queries.
    await user.click(within(await screen.findByRole('table')).getByRole('button', { name: 'Thao tác cho thẻ của Lê Lan' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Xem thẻ' }))

    const link = within(await screen.findByRole('dialog')).getByRole('link', { name: 'Tải tệp PDF' })
    expect(link).toHaveAttribute('href', '/api/v1/files/f2')
    expect(link).toHaveAttribute('download')
  })

  it('counts pending cards on ADM-1', async () => {
    fakeBackend(administrator, { 'GET /api/v1/admin/overview': overview })
    renderAt('/quan-tri')

    const tile = await screen.findByRole('link', { name: /Thẻ sinh viên chờ duyệt/ })
    expect(tile).toHaveAttribute('href', '/quan-tri/the-sinh-vien')
    expect(tile).toHaveTextContent('2')
  })
})
