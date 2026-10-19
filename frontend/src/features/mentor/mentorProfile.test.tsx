import { render, screen, waitFor } from '@testing-library/react'
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

const catalog = () =>
  json({
    categories: [{ id: 'c1', name: 'Giao diện web', skills: [{ id: 'react', name: 'React', hint: 'x' }] }],
  })

const profile = (mentor: object) => () => json({ role: 'MENTOR', githubLogin: 'hung-dev', mentor })

const draft = { approvalStatus: 'PENDING', skills: [] }

const submitted = {
  approvalStatus: 'PENDING',
  displayName: 'Đỗ Mạnh Hùng',
  experience: 'Kỹ sư phần mềm 8 năm.',
  yearsOfExperience: 8,
  linkedinUrl: 'https://www.linkedin.com/in/do-manh-hung',
  skills: [{ id: 'react', name: 'React', active: true }],
  document: { name: 'chung-chi.pdf', mediaType: 'application/pdf', size: 1258291 },
  submittedAt: '2026-10-16T03:00:00Z',
}

const refusal = (status: number, body: object) =>
  new Response(JSON.stringify({ status, ...body }), { status, headers: { 'Content-Type': 'application/problem+json' } })

afterEach(() => {
  vi.unstubAllGlobals()
})

async function fillIn(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText('Tên hiển thị *'), 'Đỗ Mạnh Hùng')
  await user.type(screen.getByLabelText('Mô tả kinh nghiệm *'), 'Kỹ sư phần mềm 8 năm.')
  await user.type(screen.getByLabelText('Số năm kinh nghiệm *'), '8')
  await user.type(screen.getByLabelText('Liên kết LinkedIn *'), 'https://www.linkedin.com/in/do-manh-hung')
  await user.type(screen.getByRole('combobox', { name: 'Kỹ năng *' }), 'react')
  await user.click(await screen.findByRole('option', { name: 'React' }))
}

describe('MEN-5, the Mentor application (FR-74, UX-DR78)', () => {
  it('states the criteria, needs the notice before the first submission, and submits', { timeout: 20_000 }, async () => {
    const user = userEvent.setup()
    let body: Record<string, unknown> | undefined
    fakeBackend(signedIn('MENTOR', 'PENDING'), {
      'GET /api/v1/profile': profile(draft),
      'GET /api/v1/catalog': catalog,
      'PUT /api/v1/profile/mentor': async (request) => {
        body = await request.json()
        return json({ submitted: true })
      },
    })
    renderAt('/ho-so')

    expect(await screen.findByText('Chưa gửi')).toBeInTheDocument()
    expect(screen.getByText('Ít nhất 2 năm kinh nghiệm làm phần mềm chuyên nghiệp')).toBeInTheDocument()
    expect(screen.getByText(/Trước khi được duyệt, bạn chưa thể nhận việc hay mời sinh viên/)).toBeInTheDocument()
    await fillIn(user)
    await user.click(screen.getByRole('button', { name: 'Gửi hồ sơ' }))
    expect(await screen.findAllByText('Hãy đồng ý với thông báo quyền riêng tư.')).not.toHaveLength(0)
    expect(body).toBeUndefined()

    await user.click(screen.getByRole('checkbox', { name: 'Tôi đã đọc và đồng ý với thông báo quyền riêng tư.' }))
    await user.click(screen.getByRole('button', { name: 'Gửi hồ sơ' }))

    await waitFor(() =>
      expect(body).toMatchObject({
        displayName: 'Đỗ Mạnh Hùng',
        yearsOfExperience: 8,
        skillIds: ['react'],
        linkedinUrl: 'https://www.linkedin.com/in/do-manh-hung',
        privacyNoticeVersion: '2.0',
      }),
    )
    expect(await screen.findByText('Đã gửi hồ sơ. Quản trị viên sẽ xem trong vài ngày.')).toBeInTheDocument()
  })

  it('validates each field and lists them in the summary', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('MENTOR', 'PENDING'), { 'GET /api/v1/profile': profile(draft), 'GET /api/v1/catalog': catalog })
    renderAt('/ho-so')

    await user.type(await screen.findByLabelText('Liên kết LinkedIn *'), 'linkedin.com/hung')
    await user.tab()
    expect(screen.getByText('Mục này chưa đúng dạng.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Gửi hồ sơ' }))

    const summary = await screen.findByRole('alert')
    expect(summary).toHaveTextContent('Tên hiển thị *')
    expect(summary).toHaveTextContent('Kỹ năng *')
  })

  it('shows a submitted application as pending, editable, with its document', async () => {
    fakeBackend(signedIn('MENTOR', 'PENDING'), { 'GET /api/v1/profile': profile(submitted), 'GET /api/v1/catalog': catalog })
    renderAt('/ho-so')

    expect(await screen.findByText('Hồ sơ đang chờ quản trị viên duyệt. Bạn sẽ nhận email khi có kết quả.')).toBeInTheDocument()
    expect(screen.getByText('Gửi 16/10. Bạn vẫn sửa được hồ sơ trong lúc chờ.')).toBeInTheDocument()
    expect(screen.getByText('Đang chờ duyệt')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Lưu thay đổi' })).toBeEnabled()
    expect(screen.queryByRole('checkbox', { name: /thông báo quyền riêng tư/ })).not.toBeInTheDocument()
    expect(screen.getByText('chung-chi.pdf · 1,2 MB')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Xóa tài liệu chung-chi.pdf' })).toBeInTheDocument()
    expect(screen.getByText('1/20 kỹ năng')).toBeInTheDocument()
  })

  it('puts a LinkedIn profile that another application holds on its field', async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('MENTOR', 'PENDING'), {
      'GET /api/v1/profile': profile(submitted),
      'GET /api/v1/catalog': catalog,
      'PUT /api/v1/profile/mentor': () => refusal(422, { code: 'GUARD_FAILED', reason: 'MENTOR_LINKEDIN_IN_USE' }),
    })
    renderAt('/ho-so')

    await user.click(await screen.findByRole('button', { name: 'Lưu thay đổi' }))

    expect(await screen.findAllByText('Liên kết LinkedIn này đã được dùng cho một hồ sơ người hướng dẫn khác.')).not.toHaveLength(0)
    expect(screen.getByLabelText('Liên kết LinkedIn *')).toHaveAttribute('aria-invalid', 'true')
  })

  it('checks a document before sending it', async () => {
    const user = userEvent.setup({ applyAccept: false })
    const seen = fakeBackend(signedIn('MENTOR', 'PENDING'), { 'GET /api/v1/profile': profile(draft) })
    renderAt('/ho-so')
    await screen.findByRole('button', { name: 'Chọn tài liệu' })

    await user.upload(document.querySelector<HTMLInputElement>('input[type="file"]')!, new File(['<svg/>'], 'cv.svg', { type: 'image/svg+xml' }))

    expect(await screen.findByText(/Không nhận SVG hoặc HTML/)).toBeInTheDocument()
    expect(seen.filter((request) => request.method === 'POST')).toHaveLength(0)
  })

  it('shows an approved application as approved and editable, without the criteria', async () => {
    fakeBackend(signedIn('MENTOR', 'APPROVED'), {
      'GET /api/v1/profile': profile({ ...submitted, approvalStatus: 'APPROVED', decidedAt: '2026-06-12T03:00:00Z' }),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/ho-so')

    expect(await screen.findByText('Duyệt ngày 12/06/2026. Thay đổi kỹ năng và mô tả hiện ngay trên hồ sơ công khai.')).toBeInTheDocument()
    expect(screen.getByText('Đã duyệt')).toBeInTheDocument()
    expect(screen.queryByText('Điều kiện để được duyệt')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Lưu thay đổi' })).toBeEnabled()
    expect(screen.getByLabelText('Tên hiển thị *')).toBeEnabled()
  })

  it('shows a rejection with its reason and keeps the application locked until the wait ends', async () => {
    const reapplyAfter = new Date(Date.now() + 10 * 24 * 60 * 60 * 1000).toISOString()
    const day = new Intl.DateTimeFormat('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh', day: '2-digit' }).format(new Date(reapplyAfter))
    fakeBackend(signedIn('MENTOR', 'REJECTED'), {
      'GET /api/v1/profile': profile({
        ...submitted,
        approvalStatus: 'REJECTED',
        decidedAt: '2026-10-18T03:00:00Z',
        rejectionReason: 'Chưa có thông tin kinh nghiệm cụ thể.',
        reapplyAfter,
      }),
      'GET /api/v1/catalog': catalog,
    })
    renderAt('/ho-so')

    expect(await screen.findByText('Bị từ chối: Chưa có thông tin kinh nghiệm cụ thể.')).toBeInTheDocument()
    expect(screen.getAllByText(new RegExp(`Bạn có thể gửi lại hồ sơ từ ngày ${day}/\\d{2}\\.`))).toHaveLength(2)
    expect(screen.getByText('Bị từ chối')).toBeInTheDocument()
    expect(screen.getByText('Điều kiện để được duyệt')).toBeInTheDocument()
    expect(screen.getByLabelText('Tên hiển thị *')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Gửi lại hồ sơ' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Xóa tài liệu chung-chi.pdf' })).toBeDisabled()
  })

  it('lets a rejected Mentor resubmit once the wait is over', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    let body: Record<string, unknown> | undefined
    fakeBackend(signedIn('MENTOR', 'REJECTED'), {
      'GET /api/v1/profile': profile({
        ...submitted,
        approvalStatus: 'REJECTED',
        decidedAt: '2026-09-01T03:00:00Z',
        rejectionReason: 'Chưa có thông tin kinh nghiệm cụ thể.',
        reapplyAfter: '2026-10-01T03:00:00Z',
      }),
      'GET /api/v1/catalog': catalog,
      'PUT /api/v1/profile/mentor': async (request) => {
        body = await request.json()
        return json({ submitted: true })
      },
    })
    renderAt('/ho-so')

    expect(await screen.findByText('Bạn đã có thể sửa và gửi lại hồ sơ.')).toBeInTheDocument()
    expect(screen.getByLabelText('Tên hiển thị *')).toBeEnabled()
    await user.click(screen.getByRole('button', { name: 'Gửi lại hồ sơ' }))

    await waitFor(() => expect(body).toMatchObject({ displayName: 'Đỗ Mạnh Hùng' }))
    expect(body).not.toHaveProperty('privacyNoticeVersion')
  })

  it('says from which day a rejected Mentor may submit again when the server refuses', { timeout: 15_000 }, async () => {
    const user = userEvent.setup()
    fakeBackend(signedIn('MENTOR', 'REJECTED'), {
      'GET /api/v1/profile': profile({ ...submitted, approvalStatus: 'REJECTED', reapplyAfter: '2026-10-01T03:00:00Z' }),
      'GET /api/v1/catalog': catalog,
      'PUT /api/v1/profile/mentor': () =>
        refusal(422, { code: 'GUARD_FAILED', reason: 'MENTOR_REAPPLY_TOO_SOON', date: '2026-11-17' }),
    })
    renderAt('/ho-so')

    await user.click(await screen.findByRole('button', { name: 'Gửi lại hồ sơ' }))

    expect(await screen.findAllByText('Bạn có thể gửi lại hồ sơ từ ngày 17/11.')).not.toHaveLength(0)
  })

  it('keeps planted payloads as text', async () => {
    const payload = '<img src=x onerror=alert(1)>'
    fakeBackend(signedIn('MENTOR', 'PENDING'), {
      'GET /api/v1/profile': profile({
        ...submitted,
        displayName: payload,
        experience: payload,
        skills: [{ id: 'x', name: payload, active: false }],
        document: { name: payload, mediaType: 'application/pdf', size: 10 },
      }),
    })
    renderAt('/ho-so')

    expect(await screen.findByLabelText('Tên hiển thị *')).toHaveValue(payload)
    expect(screen.getByLabelText('Mô tả kinh nghiệm *')).toHaveValue(payload)
    expect(screen.getByText(`${payload} · 1 KB`)).toBeInTheDocument()
    expect(document.querySelector('img[src="x"]')).toBeNull()
  })
})
