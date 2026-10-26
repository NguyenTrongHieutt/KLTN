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

describe('ADM-12 Skill suggestions (FR-73, Story 16.4)', () => {
  it('approves a suggestion with a hint, declines one with a reason, and flags an inactive name', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const approved: unknown[] = []
    const declined: unknown[] = []
    const edge = {
      id: 'g1',
      name: 'Supabase Edge',
      categoryId: 'c2',
      categoryName: 'Máy chủ và API',
      note: 'Dự án cần',
      suggesterName: 'Vy Trần',
      suggesterRole: 'STUDENT',
      createdAt: minutes(-120),
      status: 'OPEN',
    }
    const base = {
      id: 'g2',
      name: 'BASE44',
      suggesterName: 'Lan Nguyễn',
      suggesterRole: 'CUSTOMER',
      createdAt: minutes(-60),
      status: 'OPEN',
      inactiveSkill: { id: 's9', name: 'Base44' },
    }
    fakeBackend(administrator, {
      'GET /api/v1/admin/overview': () => json({ ...overview, skillSuggestions: { count: 2, oldestAt: minutes(-120) } }),
      'GET /api/v1/admin/catalog/categories': () => json(categories),
      'GET /api/v1/admin/skill-suggestions': () => json([edge, base]),
      'POST /api/v1/admin/skill-suggestions/g1/approve': async (request) => {
        approved.push(await request.json())
        return json([base, { ...edge, status: 'APPROVED', decidedAt: minutes(0), skillId: 's10' }])
      },
      'POST /api/v1/admin/skill-suggestions/g2/decline': async (request) => {
        declined.push(await request.json())
        return json([
          { ...edge, status: 'APPROVED', decidedAt: minutes(0), skillId: 's10' },
          { ...base, status: 'DECLINED', decidedAt: minutes(0), declineReason: 'Dùng lại Base44' },
        ])
      },
    })
    renderAt('/quan-tri')

    await user.click(await screen.findByRole('link', { name: /Đề xuất kỹ năng chờ duyệt/ }))
    expect(await screen.findByRole('link', { name: 'Đề xuất' })).toHaveAttribute('aria-current', 'page')
    const queue = await screen.findByRole('list', { name: 'Đề xuất kỹ năng' })
    expect(within(queue).getByText(/Trùng tên với kỹ năng đã ngừng dùng “Base44”/)).toBeInTheDocument()
    expect(within(queue).getByText(/Vy Trần \(Sinh viên\)/)).toBeInTheDocument()

    await user.click(within(queue).getAllByRole('button', { name: 'Duyệt' })[0])
    const dialog = await screen.findByRole('dialog', { name: 'Duyệt đề xuất kỹ năng' })
    expect(within(dialog).getByRole('button', { name: 'Duyệt' })).toBeDisabled()
    await user.clear(within(dialog).getByLabelText('Tên'))
    await user.type(within(dialog).getByLabelText('Tên'), 'Supabase Edge Functions')
    await user.type(within(dialog).getByLabelText('Gợi ý (hiện trong bộ chọn)'), 'Hàm chạy phía máy chủ của Supabase')
    await user.click(within(dialog).getByRole('button', { name: 'Duyệt' }))
    await waitFor(() =>
      expect(approved[0]).toEqual({
        categoryId: 'c2',
        name: 'Supabase Edge Functions',
        hint: 'Hàm chạy phía máy chủ của Supabase',
        matchLanguages: [],
        matchTools: [],
      }),
    )
    expect(await within(queue).findByText(/Đã duyệt ngày/)).toBeInTheDocument()

    await user.click(within(queue).getByRole('button', { name: 'Từ chối' }))
    const decline = await screen.findByRole('dialog', { name: 'Từ chối đề xuất “BASE44”' })
    await user.type(within(decline).getByLabelText('Lý do (người đề xuất sẽ đọc)'), 'Dùng lại Base44')
    await user.click(within(decline).getByRole('button', { name: 'Từ chối' }))
    await waitFor(() => expect(declined[0]).toEqual({ reason: 'Dùng lại Base44' }))
    expect(await within(queue).findByText(/Đã từ chối ngày .*: Dùng lại Base44/)).toBeInTheDocument()
  })
})
