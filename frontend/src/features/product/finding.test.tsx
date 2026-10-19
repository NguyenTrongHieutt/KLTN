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

const credential = {
  id: 'f1',
  category: 'CREDENTIAL',
  ruleId: 'supabase-service-role-key',
  type: 'Khóa dịch vụ Supabase',
  path: 'src/lib/supabase.ts',
  commit: '3f9a2c1' + '0'.repeat(33),
  committedAt: '2026-10-14T03:00:00Z',
  maskedPreview: 'eyJh••••••••X2kQ',
  status: 'OPEN',
  createdAt: '2026-10-16T03:02:00Z',
}

const detail = (finding: object, canResolve = true) => ({
  finding,
  productName: 'Shop Gốm Bát Tràng',
  tools: ['LOVABLE'],
  canResolve,
})

const PATH = '/san-pham/p1/van-de/f1'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('CUS-5, a credential Finding (FR-8, UX-DR30, UX-DR31)', () => {
  it('explains the Finding and links the provider key page in numbered steps', async () => {
    fakeBackend(signedIn('CUSTOMER'), { 'GET /api/v1/products/p1/findings/f1': () => json(detail(credential)) })
    renderAt(PATH)

    expect(await screen.findByRole('heading', { level: 1, name: 'Vấn đề phát hiện' })).toBeInTheDocument()
    expect(screen.getByText('Chưa xử lý')).toBeInTheDocument()
    expect(screen.getByText('Vì sao cần xử lý')).toBeInTheDocument()
    const card = screen.getByRole('article', { name: 'Khóa dịch vụ Supabase' })
    expect(within(card).getByText('src/lib/supabase.ts')).toBeInTheDocument()
    expect(within(card).getByText('3f9a2c1')).toBeInTheDocument()
    expect(within(card).getByText('eyJh••••••••X2kQ')).toBeInTheDocument()
    const link = within(card).getByRole('link', { name: /Mở trang khóa API của Supabase/ })
    expect(link).toHaveAttribute('href', 'https://supabase.com/dashboard/project/_/settings/api-keys')
    expect(link).toHaveAttribute('target', '_blank')
    expect(within(card).getByText('(supabase.com)')).toBeInTheDocument()
    expect(within(card).getByText('Bấm "Tạo khóa mới", rồi vô hiệu khóa cũ.')).toBeInTheDocument()
    expect(within(card).getByText('Trong Lovable, dán khóa mới vào phần cài đặt bí mật, không dán vào mã.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Quay lại Shop Gốm Bát Tràng' })).toHaveAttribute('href', '/san-pham/p1')
    expect(screen.getByRole('link', { name: /Đóng/ })).toHaveAttribute('href', '/san-pham/p1')
    expect(screen.queryByRole('button', { name: /Hiện|Sao chép/ })).not.toBeInTheDocument()
  })

  it('confirms the rotation in the dialog, then turns the card green', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let rotated = false
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products/p1/findings/f1': () =>
        json(
          detail(
            rotated
              ? { ...credential, status: 'RESOLVED', resolution: 'ROTATED', resolvedAt: '2026-10-19T09:30:00Z' }
              : credential,
          ),
        ),
      'POST /api/v1/products/p1/findings/f1/rotation': () => {
        rotated = true
        return json({ ...credential, status: 'RESOLVED', resolution: 'ROTATED', resolvedAt: '2026-10-19T09:30:00Z' })
      },
    })
    renderAt(PATH)

    await user.click(await screen.findByRole('button', { name: 'Tôi đã đổi khóa' }))
    const dialog = await screen.findByRole('dialog', { name: 'Xác nhận bạn đã đổi khóa?' })
    expect(within(dialog).getByText('Vấn đề được đánh dấu đã xử lý và ghi vào nhật ký hệ thống.')).toBeInTheDocument()
    expect(within(dialog).getByText('Khóa cũ vẫn nằm trong lịch sử mã, nhưng không còn dùng được.')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Quay lại' })).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Tôi đã đổi khóa' }))

    await waitFor(() => expect(rotated).toBe(true))
    expect(await screen.findByText('Đã xử lý')).toBeInTheDocument()
    expect(screen.getByText(/Bạn đã xác nhận đổi khóa lúc/)).toBeInTheDocument()
    expect(await screen.findAllByText('Đã đánh dấu đã xử lý.')).not.toHaveLength(0)
    expect(screen.queryByRole('button', { name: 'Tôi đã đổi khóa' })).not.toBeInTheDocument()
  })

  it('says so when the Finding was resolved meanwhile', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products/p1/findings/f1': () => json(detail(credential)),
      'POST /api/v1/products/p1/findings/f1/rotation': () =>
        new Response(JSON.stringify({ status: 409, code: 'CONFLICT', reason: 'FINDING_CLOSED' }), {
          status: 409,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
    })
    renderAt(PATH)

    await user.click(await screen.findByRole('button', { name: 'Tôi đã đổi khóa' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Tôi đã đổi khóa' }))

    expect(await screen.findAllByText(/Vấn đề này đã được xử lý\./)).not.toHaveLength(0)
  })

  it('offers no action to a reader who may not resolve it', async () => {
    fakeBackend(signedIn('CUSTOMER'), { 'GET /api/v1/products/p1/findings/f1': () => json(detail(credential, false)) })
    renderAt(PATH)

    expect(await screen.findByRole('article', { name: 'Khóa dịch vụ Supabase' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Tôi đã đổi khóa' })).not.toBeInTheDocument()
  })

  it('gives a type without a key page steps that say where to look', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products/p1/findings/f1': () =>
        json(detail({ ...credential, ruleId: 'jwt-signing-secret', type: 'Khóa ký JWT' })),
    })
    renderAt(PATH)

    const card = await screen.findByRole('article', { name: 'Khóa ký JWT' })
    expect(within(card).getByText('Tìm nơi máy chủ của bạn đọc chuỗi bí mật này.')).toBeInTheDocument()
    expect(within(card).queryByRole('link')).not.toBeInTheDocument()
  })

  it('finds nothing for another role', async () => {
    fakeBackend(signedIn('MENTOR'))
    renderAt(PATH)

    expect(await screen.findByText('Không tìm thấy nội dung này.')).toBeInTheDocument()
  })
})

describe('CUS-5, a personal-data Finding (FR-65)', () => {
  it('shows the file, its lines, the masked samples, and the clean-repository steps', async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/products/p1/findings/f1': () =>
        json(
          detail({
            ...credential,
            category: 'PERSONAL_DATA',
            ruleId: 'personal-data',
            type: 'Email và số điện thoại khách hàng',
            path: 'seed/customers.json',
            maskedPreview: 'ng••••@gmail.com · 09•• ••• •12',
            matchCount: 48,
          }),
        ),
    })
    renderAt(PATH)

    const card = await screen.findByRole('article', { name: 'Email và số điện thoại khách hàng' })
    expect(screen.getByText('vibefix tìm thấy dữ liệu cá nhân của khách hàng bạn trong lịch sử mã.')).toBeInTheDocument()
    expect(within(card).getByText(/48 dòng/)).toBeInTheDocument()
    expect(within(card).getByText('ng••••@gmail.com · 09•• ••• •12')).toBeInTheDocument()
    expect(within(card).getByText('Xóa tệp seed/customers.json trong Lovable, hoặc thay bằng dữ liệu giả.')).toBeInTheDocument()
    expect(within(card).queryByRole('button')).not.toBeInTheDocument()
  })
})
