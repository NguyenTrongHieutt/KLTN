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

const agreementJob = {
  id: 'j1',
  productId: 'p1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'AGREEMENT',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  problem: 'Đặt trùng bàn.',
  jobTypes: ['BUG_FIX'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-18T03:00:00Z',
  version: 3,
  mentor: { displayName: 'Đỗ Mạnh Hùng', githubLogin: 'hungdm' },
  canCancelSelection: false,
}

const now = new Date().toISOString()

const message = (overrides: object = {}) => ({
  id: 'm1',
  author: 'Đỗ Mạnh Hùng',
  mine: false,
  body: 'Chị cần tài khoản thử của Supabase không?',
  sentAt: now,
  ...overrides,
})

const customerChat = (extra: Record<string, (request: Request) => Response | Promise<Response>> = {}) =>
  fakeBackend(signedInUser, {
    ...inEveryJob('CUSTOMER'),
    'GET /api/v1/jobs/j1': () => json(agreementJob),
    'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false }),
    'POST /api/v1/jobs/j1/chat/read': () => new Response(null, { status: 204 }),
    ...extra,
  })

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('JOB-4 "Trò chuyện" (FR-46, UX-DR25, UX-DR40)', () => {
  it('shows the conversation with own and others\' bubbles and marks it read', { timeout: 20_000 }, async () => {
    let read = false
    customerChat({
      'GET /api/v1/jobs/j1/chat/messages': () =>
        json({
          items: [message(), message({ id: 'm2', author: 'Lan Nguyễn', mine: true, body: 'Chị gửi anh tài khoản thử nhé' })],
          hasOlder: false,
        }),
      'POST /api/v1/jobs/j1/chat/read': () => {
        read = true
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/cong-viec/j1/tro-chuyen')

    expect(await screen.findByRole('heading', { level: 2, name: 'Trò chuyện với người hướng dẫn' })).toBeInTheDocument()
    expect(screen.getByText('Chỉ bạn và người hướng dẫn')).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Trò chuyện' })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByText('Chị cần tài khoản thử của Supabase không?')).toBeInTheDocument()
    expect(screen.getByText('Hôm nay')).toBeInTheDocument()
    expect(screen.getByText(/^Bạn · /)).toBeInTheDocument()
    expect(screen.getByText(/^Đỗ Mạnh Hùng · /)).toBeInTheDocument()
    expect(screen.getByText('Chị gửi anh tài khoản thử nhé')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Gửi' })).toBeDisabled()
    await waitFor(() => expect(read).toBe(true))
  })

  it('keeps the thread in its own focusable scroll area, and shows the Job information once the Job runs', { timeout: 20_000 }, async () => {
    customerChat({
      'GET /api/v1/jobs/j1': () => json({ ...agreementJob, state: 'ACTIVE', agreementShared: true, agreementConfirmed: true }),
      'GET /api/v1/jobs/j1/chat/messages': () => json({ items: [message()], hasOlder: false }),
      'GET /api/v1/boards/j1/progress': () =>
        json({ state: 'ACTIVE', assessment: false, mentorName: 'Đỗ Mạnh Hùng', productName: 'Sổ đặt bàn Quán Mây', milestones: [], crew: [] }),
    })
    renderAt('/cong-viec/j1/tro-chuyen')

    const thread = await screen.findByRole('region', { name: 'Các tin nhắn' })
    expect(thread).toHaveAttribute('tabindex', '0')
    expect(await within(thread).findByText('Chị cần tài khoản thử của Supabase không?')).toBeInTheDocument()
    const aside = await screen.findByRole('complementary', { name: 'Thông tin công việc' })
    expect(aside).toHaveTextContent('Người hướng dẫn: Đỗ Mạnh Hùng')
    // The Product is in the Job's header on every tab, so the side column does not say it a second time.
    expect(aside).not.toHaveTextContent('Sổ đặt bàn Quán Mây')
  })

  it('sends a credential-like text once, as typed, with no dialog (Story 24.4a)', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const typed = `Khóa thử là ${'sk' + '_test_' + 'a1B2c3D4'.repeat(3)}`
    const sent: { body: string }[] = []
    customerChat({
      'GET /api/v1/jobs/j1/chat/messages': () =>
        json({ items: sent.map((item, index) => message({ id: `s${index}`, mine: true, body: item.body })), hasOlder: false }),
      'POST /api/v1/jobs/j1/chat/messages': async (request) => {
        const body = (await request.json()) as { body: string }
        sent.push(body)
        return json(message({ id: 'm9', mine: true, body: body.body }), 201)
      },
    })
    renderAt('/cong-viec/j1/tro-chuyen')

    expect(await screen.findByText('Chưa có tin nhắn. Hãy chào hỏi và trao đổi về kế hoạch.')).toBeInTheDocument()
    await user.type(screen.getByRole('textbox', { name: 'Tin nhắn' }), `${typed}{Enter}`)

    await waitFor(() => expect(sent).toEqual([{ body: typed }]))
    expect(await screen.findByText(typed)).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Tin nhắn' })).toHaveValue('')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Che và gửi' })).toBeNull()
    expect(screen.queryByText(/Đã che/)).toBeNull()
  })

  it('shows an https link with its domain, opening a new tab without the opener, and leaves http: as text', { timeout: 20_000 }, async () => {
    customerChat({
      'GET /api/v1/jobs/j1/chat/messages': () =>
        json({
          items: [
            message({ body: 'Tài liệu ở https://docs.google.com/document/d/abc.' }),
            message({ id: 'm2', body: 'Bản cũ: http://x.vn' }),
          ],
          hasOlder: false,
        }),
    })
    renderAt('/cong-viec/j1/tro-chuyen')

    const link = await screen.findByRole('link', { name: 'https://docs.google.com/document/d/abc' })
    expect(link).toHaveAttribute('href', 'https://docs.google.com/document/d/abc')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    expect(screen.getByText('(docs.google.com)')).toBeInTheDocument()
    expect(screen.getByText('Bản cũ: http://x.vn')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /x\.vn/ })).toBeNull()
  })

  it('pre-checks an attachment against the upload rules', { timeout: 15_000 }, async () => {
    const user = userEvent.setup({ applyAccept: false })
    customerChat({ 'GET /api/v1/jobs/j1/chat/messages': () => json({ items: [], hasOlder: false }) })
    renderAt('/cong-viec/j1/tro-chuyen')
    await screen.findByText('Chưa có tin nhắn. Hãy chào hỏi và trao đổi về kế hoạch.')
    const input = document.querySelector('input[type="file"]') as HTMLInputElement

    const big = new File(['x'], 'lon.pdf', { type: 'application/pdf' })
    Object.defineProperty(big, 'size', { value: 11 * 1024 * 1024 })
    await user.upload(input, big)
    expect((await screen.findAllByText('Tệp lớn hơn 10 MB nên không gửi được.')).length).toBeGreaterThan(0)

    await user.upload(input, new File(['<svg/>'], 'hinh.svg', { type: 'image/svg+xml' }))
    expect((await screen.findAllByText('Chỉ gửi được ảnh, tệp PDF hoặc tệp văn bản.')).length).toBeGreaterThan(0)

    await user.upload(input, new File(['%PDF-1.4'], 'ke-hoach.pdf', { type: 'application/pdf' }))
    expect(await screen.findByText('ke-hoach.pdf')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Gửi' })).toBeEnabled()
  })

  it('loads older messages on "Xem tin cũ hơn"', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    customerChat({
      'GET /api/v1/jobs/j1/chat/messages': (request) =>
        new URL(request.url).searchParams.get('before') === 'm5'
          ? json({ items: [message({ id: 'm1', body: 'Tin cũ nhất' })], hasOlder: false })
          : json({ items: [message({ id: 'm5', body: 'Tin mới' })], hasOlder: true }),
    })
    renderAt('/cong-viec/j1/tro-chuyen')

    await user.click(await screen.findByRole('button', { name: 'Xem tin cũ hơn' }))

    expect(await screen.findByText('Tin cũ nhất')).toBeInTheDocument()
    expect(screen.getByText('Tin mới')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Xem tin cũ hơn' })).toBeNull())
  })

  it('keeps planted payloads in messages as text (SG-30)', { timeout: 15_000 }, async () => {
    customerChat({
      'GET /api/v1/jobs/j1/chat/messages': () =>
        json({ items: PAYLOADS.map((payload, index) => message({ id: `p${index}`, body: payload, author: payload })), hasOlder: false }),
    })
    renderAt('/cong-viec/j1/tro-chuyen')

    for (const payload of PAYLOADS) {
      expect((await screen.findAllByText(payload)).length).toBeGreaterThan(0)
    }
    expect(document.querySelector('img[src="x"], script, svg[onload]')).toBeNull()
  })

  it('shows the Mentor\'s "Trò chuyện" tab with the unread dot from Agreement on', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () =>
        json({
          id: 'j1',
          title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
          productName: 'Sổ đặt bàn Quán Mây',
          state: 'AGREEMENT',
          version: 3,
          jobTypes: ['BUG_FIX'],
          selectedAt: '2026-10-18T03:00:00Z',
          codeJob: true,
          commitEmailNoticeAcknowledged: true,
        }),
      'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: true }),
    })
    renderAt('/cong-viec/j1')

    const tab = await screen.findByRole('link', { name: /Trò chuyện/ })
    expect(tab).toHaveAttribute('href', '/cong-viec/j1/tro-chuyen')
    expect(await within(tab).findByText('có tin mới')).toBeInTheDocument()
  })

  it('shows the not-found screen to a Crew member', { timeout: 15_000 }, async () => {
    fakeBackend(signedInUser, inEveryJob('CREW'))
    renderAt('/cong-viec/j1/tro-chuyen')

    expect(await screen.findByRole('heading', { name: /Không tìm thấy/ })).toBeInTheDocument()
  })
})
