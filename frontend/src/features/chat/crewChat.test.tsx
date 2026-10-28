import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, signedInUser, inEveryJob, heldJobs } from '@/test/fakeBackend'

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

const now = new Date().toISOString()

const crewChat = (overrides: object = {}) => ({
  chatId: 'cc1',
  title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
  unread: false,
  readOnly: false,
  closed: false,
  ...overrides,
})

const message = (overrides: object = {}) => ({
  id: 'm1',
  author: 'Đỗ Mạnh Hùng',
  mine: false,
  body: 'Vy viết giúp anh ghi chú cài đặt cho chị Lan nhé',
  maskedCredentials: 0,
  maskedPersonal: 0,
  sentAt: now,
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

describe('JOB-8 "Trao đổi nhóm" (FR-77, UX-DR80)', () => {
  it('gives the Mentor a tab with its dot and a thread the Customer cannot read', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json(mentorJob),
      'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false, readOnly: false }),
      'GET /api/v1/jobs/j1/crew-chat': () => json(crewChat({ unread: true })),
      'GET /api/v1/crew-chats/cc1/messages': () =>
        json({ items: [message({ author: 'Trần Thảo Vy', body: 'Em viết xong ghi chú rồi ạ' })], hasOlder: false }),
      'POST /api/v1/crew-chats/cc1/read': () => new Response(null, { status: 204 }),
      'POST /api/v1/crew-chats/cc1/messages': async (request) => {
        sent.push(await request.json())
        return json(message({ id: 'm2', mine: true, body: 'Cảm ơn Vy' }), 201)
      },
    })
    renderAt('/cong-viec/j1/trao-doi-nhom')

    const tab = await screen.findByRole('link', { name: /Trao đổi nhóm/ })
    expect(tab).toHaveAttribute('aria-current', 'page')
    expect(await within(tab).findByText('có tin mới')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Trao đổi trong nhóm thực hiện' })).toBeInTheDocument()
    expect(screen.getByText('Chỉ người hướng dẫn và nhóm thực hiện; khách hàng không xem được')).toBeInTheDocument()
    expect(await screen.findByText('Em viết xong ghi chú rồi ạ')).toBeInTheDocument()

    await user.type(screen.getByRole('textbox', { name: 'Tin nhắn' }), 'Cảm ơn Vy')
    await user.click(screen.getByRole('button', { name: 'Gửi' }))
    await waitFor(() => expect(sent).toEqual([{ body: 'Cảm ơn Vy', sensitive: 'CHECK' }]))
  })

  it('lets an active Crew member reach it from "Việc tôi tham gia"', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      ...heldJobs({ jobId: 'j1', title: 'Sửa lỗi đặt bàn và thêm nhắc lịch', role: 'CREW', crewKind: 'ACTIVE' }),
      'GET /api/v1/crew/assignments': () =>
        json([
          {
            jobId: 'j1',
            title: 'Sửa lỗi đặt bàn và thêm nhắc lịch',
            productName: 'Sổ đặt bàn Quán Mây',
            mentorName: 'Đỗ Mạnh Hùng',
            status: 'ACTIVE',
            scope: 'Sửa lỗi trùng giờ',
            versionNo: 1,
            sharePercent: 40,
            signed: true,
            codeJob: true,
            milestones: [],
          },
        ]),
      'GET /api/v1/team': () => json({ team: null }),
      'GET /api/v1/jobs/j1/crew-chat': () => json(crewChat()),
      'GET /api/v1/crew-chats/cc1/messages': () => json({ items: [message()], hasOlder: false }),
      'POST /api/v1/crew-chats/cc1/read': () => new Response(null, { status: 204 }),
    })
    renderAt('/cong-viec')

    await user.click(await screen.findByRole('link', { name: 'Trao đổi nhóm' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Sửa lỗi đặt bàn và thêm nhắc lịch' })).toBeInTheDocument()
    expect(await screen.findByText('Vy viết giúp anh ghi chú cài đặt cho chị Lan nhé')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Tin nhắn' })).toBeInTheDocument()
  })

  it('shows a former member their window read-only', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('CREW'),
      'GET /api/v1/jobs/j1/crew-chat': () => json(crewChat({ readOnly: true })),
      'GET /api/v1/crew-chats/cc1/messages': () => json({ items: [message()], hasOlder: false }),
      'POST /api/v1/crew-chats/cc1/read': () => new Response(null, { status: 204 }),
    })
    renderAt('/cong-viec/j1/trao-doi-nhom')

    expect(await screen.findByText('Vy viết giúp anh ghi chú cài đặt cho chị Lan nhé')).toBeInTheDocument()
    expect(screen.getByText(/Bạn chỉ xem được các tin nhắn trong thời gian bạn tham gia/)).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Tin nhắn' })).toBeNull()
  })

  it('is not found for a Customer', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, inEveryJob('CUSTOMER'))
    renderAt('/cong-viec/j1/trao-doi-nhom')
    expect(await screen.findByRole('heading', { name: /Không tìm thấy/ })).toBeInTheDocument()
  })

  it('is not found for a user the Crew Chat does not name', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, inEveryJob('CREW'))
    renderAt('/cong-viec/j1/trao-doi-nhom')
    expect(await screen.findByRole('heading', { name: /Không tìm thấy/ })).toBeInTheDocument()
  })

  it('shows a closed Job Chat read-only', { timeout: 20_000 }, async () => {
    fakeBackend(signedInUser, {
      ...inEveryJob('MENTOR'),
      'GET /api/v1/mentor/jobs/j1': () => json({ ...mentorJob, state: 'CLOSED' }),
      'GET /api/v1/jobs/j1/chat': () => json({ chatId: 'c1', unread: false, readOnly: true }),
      'GET /api/v1/jobs/j1/crew-chat': () => json(crewChat({ readOnly: true, closed: true })),
      'GET /api/v1/jobs/j1/chat/messages': () => json({ items: [message({ body: 'Cảm ơn anh' })], hasOlder: false }),
      'POST /api/v1/jobs/j1/chat/read': () => new Response(null, { status: 204 }),
    })
    renderAt('/cong-viec/j1/tro-chuyen')

    expect(await screen.findByText('Cảm ơn anh')).toBeInTheDocument()
    expect(await screen.findByText('Công việc đã kết thúc. Cuộc trò chuyện chỉ còn để xem.')).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Tin nhắn' })).toBeNull()
  })
})
