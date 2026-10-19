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

const profile = () => json({ role: 'STUDENT', githubLogin: 'khoa-dev', customer: null, student: null })

const pendingCard = {
  status: 'UNVERIFIED',
  renewalOpen: false,
  studentCard: { status: 'PENDING', uploadedAt: '2026-10-19T02:41:00Z', fileName: 'the.jpg' },
}

const rejectedCard = {
  status: 'UNVERIFIED',
  renewalOpen: false,
  studentCard: {
    status: 'REJECTED',
    uploadedAt: '2026-10-18T02:41:00Z',
    decidedAt: '2026-10-19T01:00:00Z',
    fileName: 'the.jpg',
    rejectionReason: 'Ảnh mờ, không đọc được',
  },
}

function file(name: string, type: string, size = 2048) {
  const value = new File(['x'], name, { type })
  Object.defineProperty(value, 'size', { value: size })
  return value
}

async function openCardTab(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('tab', { name: 'Thẻ sinh viên' }))
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('STU-5 student card (FR-5, UX-DR45, UX-DR27)', () => {
  it('offers the card as the second way, says who sees it, and shows it pending once uploaded', async () => {
    const user = userEvent.setup()
    let view: object = { status: 'UNVERIFIED', renewalOpen: false }
    const seen = fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () => json(view),
      'POST /api/v1/verification/student-card': () => {
        view = pendingCard
        return json(view, 201)
      },
    })
    renderAt('/ho-so')

    expect(await screen.findByRole('tablist', { name: 'Cách xác minh' })).toBeInTheDocument()
    await openCardTab(user)
    expect(
      screen.getByText('Ảnh hoặc PDF, tối đa 10 MB. Chỉ quản trị viên xem được; tự xóa 30 ngày sau khi có quyết định.'),
    ).toBeInTheDocument()
    const input = document.querySelector<HTMLInputElement>('input[type="file"]')!
    expect(input.accept).toContain('application/pdf')
    await user.upload(input, file('the.jpg', 'image/jpeg'))

    const status = await screen.findByText('Thẻ sinh viên đang chờ quản trị viên duyệt.')
    expect(status.closest('[role="status"]')).toHaveTextContent('Đã tải lên the.jpg lúc')
    expect(screen.getByText('Đang chờ duyệt')).toBeInTheDocument()
    const upload = seen.find((request) => request.method === 'POST')!
    expect(new URL(upload.url).pathname).toBe('/api/v1/verification/student-card')
    expect(upload.headers.get('Content-Type')).toMatch(/^multipart\/form-data; boundary=/)
  })

  it('checks the size and the type before sending anything', async () => {
    const user = userEvent.setup({ applyAccept: false })
    const seen = fakeBackend(signedIn('STUDENT'), { 'GET /api/v1/profile': profile })
    renderAt('/ho-so')
    await openCardTab(user)
    const input = document.querySelector<HTMLInputElement>('input[type="file"]')!

    await user.upload(input, file('lon.jpg', 'image/jpeg', 10 * 1024 * 1024 + 1))
    expect(await screen.findByRole('alert')).toHaveTextContent('Tệp lớn hơn 10 MB.')
    await user.upload(input, file('the.svg', 'image/svg+xml'))
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Chỉ nhận ảnh, PDF, tệp văn bản và tệp nén. Không nhận SVG hoặc HTML.',
    )
    await user.upload(input, file('the.html', 'text/html'))
    expect(screen.getByRole('alert')).toHaveTextContent('Không nhận SVG hoặc HTML.')

    expect(seen.filter((request) => request.method === 'POST')).toHaveLength(0)
  })

  it("says the server's refusal in its own words", async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'POST /api/v1/verification/student-card': () =>
        new Response(JSON.stringify({ status: 429, code: 'RATE_LIMITED', reason: 'UPLOAD_QUOTA' }), {
          status: 429,
          headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '600' },
        }),
    })
    renderAt('/ho-so')
    await openCardTab(user)

    await user.upload(document.querySelector<HTMLInputElement>('input[type="file"]')!, file('the.png', 'image/png'))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Bạn đã tải lên quá nhiều tệp trong một giờ. Hãy thử lại sau.',
    )
  })

  it('shows a rejection with its reason, offers another card, and the school email', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () => json(rejectedCard),
    })
    renderAt('/ho-so')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Thẻ sinh viên bị từ chối: Ảnh mờ, không đọc được')
    expect(alert).toHaveTextContent('Hãy chụp lại thẻ ở nơi đủ sáng')
    expect(screen.getByText('Bị từ chối')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tải lên thẻ khác' })).toBeEnabled()

    await user.click(screen.getByRole('button', { name: 'Dùng email trường' }))
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Email trường' })).toBeVisible())
  })

  it('opens on the card tab while a card waits, with no way to upload another', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () => json(pendingCard),
    })
    renderAt('/ho-so')

    const tab = await screen.findByRole('tab', { name: 'Thẻ sinh viên' })
    expect(tab).toHaveAttribute('aria-selected', 'true')
    const panel = screen.getByRole('tabpanel')
    expect(within(panel).getByText('Thẻ sinh viên đang chờ quản trị viên duyệt.')).toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'Chọn ảnh thẻ' })).not.toBeInTheDocument()
  })

  it('names the card as the way a verification was made', async () => {
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () =>
        json({
          status: 'VERIFIED',
          method: 'STUDENT_CARD',
          verifiedUntil: '2027-09-30',
          verifiedAt: '2026-10-19T02:45:00Z',
          renewalOpen: false,
        }),
    })
    renderAt('/ho-so')

    expect(await screen.findByText(/^Xác minh bằng thẻ sinh viên ngày 19\/10\/2026\./)).toBeInTheDocument()
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
  })

  it('renews from the school email even while the card tab is open', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': profile,
      'GET /api/v1/verification': () =>
        json({
          ...rejectedCard,
          status: 'EXPIRED',
          method: 'STUDENT_CARD',
          verifiedUntil: '2026-09-30',
          verifiedAt: '2025-10-01T02:00:00Z',
        }),
    })
    renderAt('/ho-so')

    expect(await screen.findByRole('tab', { name: 'Thẻ sinh viên' })).toHaveAttribute('aria-selected', 'true')
    await user.click(screen.getByRole('button', { name: 'Gia hạn' }))

    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Email trường' })).toHaveFocus())
    expect(screen.getByText('Đã xác minh bằng thẻ sinh viên đến 30/09/2026.')).toBeInTheDocument()
  })
})
