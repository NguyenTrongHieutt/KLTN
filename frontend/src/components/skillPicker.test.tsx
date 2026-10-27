import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/routes'
import { fakeBackend, json, profileView, signedIn, signedInWithGoogle } from '@/test/fakeBackend'

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={createQueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

const many = Array.from({ length: 20 }, (_, index) => ({ id: `s${index}`, name: `Kỹ năng ${index}`, hint: 'x' }))

const catalog = () =>
  json({
    categories: [
      {
        id: 'c1',
        name: 'Giao diện web',
        skills: [
          { id: 'react', name: 'React', hint: 'Thư viện dựng giao diện web.' },
          { id: 'ts', name: 'TypeScript', hint: 'JavaScript có kiểm tra kiểu.' },
        ],
      },
      {
        id: 'c6',
        name: 'Bảo mật và kiểm thử',
        skills: [
          { id: 'owasp', name: 'Bảo mật web (OWASP Top 10)', hint: 'Chặn lỗi bảo mật thường gặp.' },
          { id: 'unit', name: 'Kiểm thử đơn vị', hint: 'Mã tự kiểm tra.' },
        ],
      },
      { id: 'c9', name: 'Khác', skills: many },
    ],
  })

const profile = (skills: { id: string; name: string; active: boolean }[] = []) =>
  json(profileView({ displayName: 'Minh', skills }))

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('the Skill picker on ACC-1 (FR-4, UX-DR75, SG-50)', () => {
  it('filters without accents or letter case, groups by Category, and saves the chosen IDs', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let body: unknown
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () => profile(),
      'GET /api/v1/catalog': catalog,
      'PUT /api/v1/profile': async (request) => {
        body = await request.json()
        return new Response(null, { status: 204 })
      },
    })
    renderAt('/ho-so')

    const input = await screen.findByRole('combobox', { name: 'Kỹ năng' })
    await user.type(input, 'BAO MAT')
    const listbox = await screen.findByRole('listbox')
    expect(within(listbox).getByRole('option', { name: 'Bảo mật web (OWASP Top 10)' })).toBeInTheDocument()
    expect(within(listbox).queryByRole('option', { name: 'React' })).not.toBeInTheDocument()
    expect(within(listbox).getByText('Bảo mật và kiểm thử')).toBeInTheDocument()
    await user.click(within(listbox).getByRole('option', { name: 'Bảo mật web (OWASP Top 10)' }))

    expect(await screen.findByRole('button', { name: 'Bỏ Bảo mật web (OWASP Top 10)' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Lưu hồ sơ' }))
    await waitFor(() => expect(body).toMatchObject({ skillIds: ['owasp'] }))
  })

  it('says when nothing matches', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), { 'GET /api/v1/profile': () => profile(), 'GET /api/v1/catalog': catalog })
    renderAt('/ho-so')

    await user.type(await screen.findByRole('combobox', { name: 'Kỹ năng' }), 'cobol')

    expect(await screen.findByText('Không có kỹ năng “cobol” trong danh sách.')).toBeInTheDocument()
  })

  it('waits for the IME to finish a letter before filtering', async () => {
    fakeBackend(signedIn('STUDENT'), { 'GET /api/v1/profile': () => profile(), 'GET /api/v1/catalog': catalog })
    renderAt('/ho-so')
    const input = await screen.findByRole('combobox', { name: 'Kỹ năng' })

    fireEvent.compositionStart(input)
    fireEvent.change(input, { target: { value: 'ks' } })
    const listbox = await screen.findByRole('listbox')
    expect(within(listbox).getByRole('option', { name: 'React' })).toBeInTheDocument()

    fireEvent.change(input, { target: { value: 'kiể' } })
    fireEvent.compositionEnd(input)
    await waitFor(() => expect(within(listbox).queryByRole('option', { name: 'React' })).not.toBeInTheDocument())
    expect(within(listbox).getByRole('option', { name: 'Kiểm thử đơn vị' })).toBeInTheDocument()
  })

  it('stops at 20 and keeps a retired Skill as a token', async () => {
    const user = userEvent.setup()
    const twenty = [
      { id: 'retired', name: 'Kỹ năng cũ', active: false },
      ...many.slice(0, 19).map((skill) => ({ id: skill.id, name: skill.name, active: true })),
    ]
    fakeBackend(signedIn('STUDENT'), { 'GET /api/v1/profile': () => profile(twenty), 'GET /api/v1/catalog': catalog })
    renderAt('/ho-so')

    expect(await screen.findByRole('button', { name: 'Bỏ Kỹ năng cũ' })).toBeInTheDocument()
    expect(screen.getByText('(ngừng dùng)')).toBeInTheDocument()
    await user.click(screen.getByRole('combobox', { name: 'Kỹ năng' }))
    const listbox = await screen.findByRole('listbox')
    expect(await screen.findByText('Tối đa 20 kỹ năng')).toBeInTheDocument()
    expect(within(listbox).getByRole('option', { name: 'React' })).toHaveAttribute('aria-disabled', 'true')
  })

  it("shows the server's Skill refusal on the field", { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () => profile([{ id: 'react', name: 'React', active: true }]),
      'GET /api/v1/catalog': catalog,
      'PUT /api/v1/profile': () =>
        new Response(JSON.stringify({ status: 400, code: 'VALIDATION', reason: 'SKILL_LIMIT', limit: 20 }), {
          status: 400,
          headers: { 'Content-Type': 'application/problem+json' },
        }),
    })
    renderAt('/ho-so')

    await user.click(await screen.findByRole('button', { name: 'Lưu hồ sơ' }))

    expect(await screen.findAllByText('Tối đa 20 kỹ năng.')).not.toHaveLength(0)
    expect(screen.getByRole('combobox', { name: 'Kỹ năng' })).toHaveAttribute('aria-invalid', 'true')
  })
})

const problem = (status: number, body: Record<string, unknown>) =>
  new Response(JSON.stringify({ status, ...body }), { status, headers: { 'Content-Type': 'application/problem+json' } })

describe('suggesting a missing Skill from the picker (FR-73, Story 16.4)', () => {
  it('sends the typed name, says it was sent, and offers an existing Skill instead', { timeout: 30_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () => profile(),
      'GET /api/v1/catalog': catalog,
      'POST /api/v1/skill-suggestions': async (request) => {
        const body = (await request.json()) as { name: string }
        sent.push(body)
        return body.name === 'Reactjs'
          ? problem(409, { code: 'CONFLICT', reason: 'SKILL_ALREADY_EXISTS', skillId: 'react', skillName: 'React' })
          : json({ id: 'g1', name: body.name, status: 'OPEN', createdAt: new Date().toISOString() }, 201)
      },
    })
    renderAt('/ho-so')

    const input = await screen.findByRole('combobox', { name: 'Kỹ năng' })
    await user.type(input, 'Supabase Edge')
    await user.click(await screen.findByRole('button', { name: 'Đề xuất thêm kỹ năng này' }))
    let dialog = await screen.findByRole('dialog', { name: 'Đề xuất kỹ năng' })
    expect(within(dialog).getByLabelText('Tên kỹ năng')).toHaveValue('Supabase Edge')
    await user.selectOptions(within(dialog).getByLabelText('Nhóm kỹ năng (không bắt buộc)'), 'c6')
    await user.type(within(dialog).getByLabelText('Ghi chú (không bắt buộc)'), 'Dự án cần hàm phía máy chủ')
    await user.click(within(dialog).getByRole('button', { name: 'Gửi đề xuất' }))
    expect(await within(dialog).findByRole('status')).toHaveTextContent('Đã gửi đề xuất.')
    expect(sent[0]).toEqual({ name: 'Supabase Edge', categoryId: 'c6', note: 'Dự án cần hàm phía máy chủ' })
    await user.click(within(dialog).getByRole('button', { name: 'Đóng' }))

    await user.clear(input)
    await user.type(input, 'Reactjs')
    await user.click(await screen.findByRole('button', { name: 'Đề xuất thêm kỹ năng này' }))
    dialog = await screen.findByRole('dialog', { name: 'Đề xuất kỹ năng' })
    await user.click(within(dialog).getByRole('button', { name: 'Gửi đề xuất' }))
    expect(await within(dialog).findByText('Kỹ năng “React” đã có trong danh sách.')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Chọn kỹ năng này' }))
    expect(await screen.findByRole('button', { name: 'Bỏ React' })).toBeInTheDocument()
  })

  it('offers the suggestion to a user with no role too (Story 20.3)', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedInWithGoogle, { 'GET /api/v1/profile': () => profile(), 'GET /api/v1/catalog': catalog })
    renderAt('/ho-so')

    await user.type(await screen.findByRole('combobox', { name: 'Kỹ năng' }), 'Supabase Edge')
    expect(await screen.findByRole('button', { name: 'Đề xuất thêm kỹ năng này' })).toBeInTheDocument()
  })

  it('says when the day’s five suggestions are used', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('STUDENT'), {
      'GET /api/v1/profile': () => profile(),
      'GET /api/v1/catalog': catalog,
      'POST /api/v1/skill-suggestions': () => problem(429, { code: 'RATE_LIMITED', reason: 'SKILL_SUGGESTION_LIMIT' }),
    })
    renderAt('/ho-so')

    await user.type(await screen.findByRole('combobox', { name: 'Kỹ năng' }), 'Thanh toán MoMo')
    await user.click(await screen.findByRole('button', { name: 'Đề xuất thêm kỹ năng này' }))
    const dialog = await screen.findByRole('dialog', { name: 'Đề xuất kỹ năng' })
    await user.click(within(dialog).getByRole('button', { name: 'Gửi đề xuất' }))
    expect(await within(dialog).findByText('Bạn đã gửi đủ 5 đề xuất hôm nay. Hãy thử lại vào ngày mai.')).toBeInTheDocument()
  })
})
