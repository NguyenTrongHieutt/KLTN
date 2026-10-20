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
    email: 'phuong@vibefix.vn',
    displayName: 'Hà Phương',
    idleExpiresAt: minutes(30),
    absoluteExpiresAt: minutes(480),
  },
}

afterEach(() => {
  vi.unstubAllGlobals()
})

const overview = {
  failedOperations: { count: 0 },
  stalledEvents: { count: 0 },
  activeAdministrators: 2,
  studentCards: { count: 0 },
  mentorApplications: { count: 0 },
  disputes: { count: 0 },
  lowFeedback: { count: 0 },
  failedExecutions: { count: 0 },
  falsePositives: { count: 0 },
}

const categories = [
  { id: 'c1', name: 'Giao diện web', displayOrder: 10, active: true, activeSkills: 1 },
  { id: 'c2', name: 'Máy chủ và API', displayOrder: 20, active: true, activeSkills: 0 },
]

const react = {
  id: 's1',
  categoryId: 'c1',
  categoryName: 'Giao diện web',
  name: 'React',
  hint: 'Thư viện giao diện',
  matchLanguages: ['JavaScript'],
  matchTools: [],
  active: true,
}

describe('ADM-12 Skill catalog (FR-72)', () => {
  it('adds a Skill, refuses a taken name, and reorders the Categories', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const created: unknown[] = []
    const orders: unknown[] = []
    let taken = false
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json(overview),
      'GET /api/v1/admin/catalog/categories': () => json(categories),
      'GET /api/v1/admin/catalog/skills': () => json({ items: [react], total: 1, page: 0, size: 25 }),
      'POST /api/v1/admin/catalog/skills': async (request) => {
        created.push(await request.json())
        if (taken) {
          return new Response(JSON.stringify({ status: 409, code: 'CONFLICT', reason: 'SKILL_NAME_TAKEN' }), {
            status: 409,
            headers: { 'Content-Type': 'application/problem+json' },
          })
        }
        taken = true
        return json({ items: [react], total: 1, page: 0, size: 25 }, 201)
      },
      'PUT /api/v1/admin/catalog/categories/order': async (request) => {
        orders.push(await request.json())
        return json([...categories].reverse())
      },
    })
    renderAt('/quan-tri/ky-nang')

    expect(await screen.findByText('Thư viện giao diện')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Thêm kỹ năng' }))
    let dialog = await screen.findByRole('dialog', { name: 'Thêm kỹ năng' })
    await user.type(within(dialog).getByLabelText('Tên'), 'Svelte')
    await user.type(within(dialog).getByLabelText('Gợi ý (hiện trong bộ chọn)'), 'Giao diện viết bằng Svelte')
    await user.type(within(dialog).getByLabelText('Ngôn ngữ GitHub khớp (cách nhau bằng dấu phẩy)'), 'Svelte, TypeScript')
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }))
    await waitFor(() =>
      expect(created[0]).toEqual({
        categoryId: 'c1',
        name: 'Svelte',
        hint: 'Giao diện viết bằng Svelte',
        matchLanguages: ['Svelte', 'TypeScript'],
        matchTools: [],
      }),
    )

    await user.click(screen.getByRole('button', { name: 'Thêm kỹ năng' }))
    dialog = await screen.findByRole('dialog', { name: 'Thêm kỹ năng' })
    await user.type(within(dialog).getByLabelText('Tên'), 'svelte')
    await user.type(within(dialog).getByLabelText('Gợi ý (hiện trong bộ chọn)'), 'Trùng')
    await user.click(within(dialog).getByRole('button', { name: 'Lưu' }))
    expect(await within(dialog).findByText(/Đã có kỹ năng mang tên này\./)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Quay lại' }))

    await user.click(screen.getByRole('button', { name: 'Đưa Máy chủ và API lên' }))
    await waitFor(() => expect(orders).toEqual([{ ids: ['c2', 'c1'] }]))
  })
})
