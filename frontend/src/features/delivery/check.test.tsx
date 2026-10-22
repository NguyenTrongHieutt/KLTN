import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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

beforeEach(() => {
  window.sessionStorage.clear()
})

afterEach(() => {
  vi.unstubAllGlobals()
})

const customerJob = {
  id: 'j1',
  productId: 'pr1',
  productName: 'Sổ đặt bàn Quán Mây',
  state: 'ACTIVE',
  title: 'Đánh giá sổ đặt bàn',
  jobTypes: ['ASSESSMENT'],
  skills: [],
  screenshots: [],
  createdAt: '2026-10-12T03:00:00Z',
  updatedAt: '2026-10-18T03:00:00Z',
  version: 12,
  agreementShared: true,
  agreementConfirmed: true,
  canCancelSelection: false,
}

const criterion = (id: string, number: number, statement: string) => ({
  criterionId: id,
  number,
  statement,
  checkSteps: '1. Mở báo cáo. 2. Đếm các mục.',
  met: true,
  note: `Ghi chú ${number} của người hướng dẫn.`,
})

const report = (overrides: object = {}) => ({
  id: 'h1',
  jobId: 'j1',
  jobTitle: 'Đánh giá sổ đặt bàn',
  number: 1,
  milestoneTitle: 'Báo cáo',
  round: 1,
  status: 'SUBMITTED',
  mentorName: 'Đỗ Mạnh Hùng',
  createdAt: '2026-10-20T03:00:00Z',
  checkBy: '2026-10-27T03:00:00Z',
  assessment: true,
  audience: 'CUSTOMER',
  criteria: [criterion('c1', 1, 'Báo cáo nêu đủ chín mục'), criterion('c2', 2, 'Có giải thích thuật ngữ')],
  itemNotes: [],
  version: 13,
  roundsLeft: 2,
  decision: { releaseVnd: 1000001, revisionAllowed: true },
  checks: [],
  preview: {},
  screenshots: [],
  ...overrides,
})

const list = { jobId: 'j1', title: 'Đánh giá sổ đặt bàn', audience: 'CUSTOMER', assessment: true, handovers: [] }

describe('JOB-5 guided check (FR-33, FR-34, UX-DR33)', () => {
  it('walks the criteria one by one and asks for a revision of the one not met', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/handovers': () => json(list),
      'GET /api/v1/handovers/h1': () => json(report()),
      'POST /api/v1/handovers/h1/revision': async (request) => {
        sent.push(await request.json())
        return json(report({ status: 'REVISION_REQUESTED', decision: null }))
      },
    })
    renderAt('/cong-viec/j1/ban-giao/h1')

    expect(await screen.findByText(/Giai đoạn này còn 2 lượt sửa/)).toBeInTheDocument()
    await user.click(screen.getByRole('link', { name: 'Kiểm tra từng tiêu chí' }))

    expect(await screen.findByText('Tiêu chí 1/2')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1, name: 'Báo cáo nêu đủ chín mục' })).toBeInTheDocument()
    expect(screen.getByText('Mở báo cáo.')).toBeInTheDocument()
    expect(screen.getByText('Đếm các mục.')).toBeInTheDocument()
    expect(screen.getByText('Ghi chú 1 của người hướng dẫn.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Tiếp' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Hãy chọn Đạt hoặc Chưa đạt trước khi tiếp tục.')
    await user.click(screen.getByRole('radio', { name: 'Đạt' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp' }))

    expect(await screen.findByText('Tiêu chí 2/2')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'Chưa đạt' }))
    await user.type(screen.getByRole('textbox', { name: 'Nhận xét của bạn (không bắt buộc)' }), 'Thiếu ba thuật ngữ.')
    await user.click(screen.getByRole('button', { name: 'Tiếp' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Tóm tắt kiểm tra' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Nghiệm thu giai đoạn' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Yêu cầu sửa (1 tiêu chí)' }))
    const dialog = await screen.findByRole('dialog', { name: 'Yêu cầu sửa 1 tiêu chí?' })
    expect(within(dialog).getByText(/còn 1 lượt sau đó/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))

    await waitFor(() =>
      expect(sent).toEqual([
        {
          version: 13,
          checks: [
            { criterionId: 'c1', met: true },
            { criterionId: 'c2', met: false, comment: 'Thiếu ba thuật ngữ.' },
          ],
          sensitive: 'CHECK',
        },
      ]),
    )
    expect(await screen.findByRole('heading', { level: 2, name: 'Báo cáo bàn giao · Giai đoạn 1 · Báo cáo' })).toBeInTheDocument()
    expect(window.sessionStorage.getItem('vibefix.check.h1')).toBeNull()
  })

  it('resumes a draft after a reload and accepts with the amount and the test tag', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    const sent: unknown[] = []
    window.sessionStorage.setItem('vibefix.check.h1', JSON.stringify({ step: 1, marks: { c1: { met: true, comment: '' } } }))
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/handovers/h1': () => json(report()),
      'POST /api/v1/handovers/h1/accept': async (request) => {
        sent.push(await request.json())
        return json(report({ status: 'ACCEPTED', decision: null }))
      },
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByText('Tiêu chí 2/2')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'Đạt' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp' }))
    expect(screen.queryByRole('button', { name: /Yêu cầu sửa/ })).toBeNull()
    await user.click(await screen.findByRole('button', { name: 'Nghiệm thu giai đoạn' }))
    const dialog = await screen.findByRole('dialog', { name: 'Nghiệm thu Giai đoạn 1?' })
    expect(within(dialog).getByText(/1\.000\.001/)).toBeInTheDocument()
    expect(within(dialog).getByText('THỬ NGHIỆM')).toBeInTheDocument()
    expect(within(dialog).getByText(/không hoàn tác được/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))
    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toMatchObject({ version: 13, checks: [{ criterionId: 'c1', met: true }, { criterionId: 'c2', met: true }] })
  })

  it('says a Code Job acceptance merges first, and pays once the merge is done (FR-33)', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    window.sessionStorage.setItem('vibefix.check.h1', JSON.stringify({ step: 1, marks: { c1: { met: true, comment: '' } } }))
    const merging = report({ assessment: false, acceptance: 'MERGING', decision: null })
    let accepted = false
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/handovers/h1': () => json(accepted ? merging : report({ assessment: false })),
      'POST /api/v1/handovers/h1/accept': () => {
        accepted = true
        return json(merging)
      },
      'GET /api/v1/jobs/j1': () => json(customerJob),
      'GET /api/v1/boards/j1/handovers': () => json(list),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByText('Tiêu chí 2/2')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'Đạt' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp' }))
    await user.click(await screen.findByRole('button', { name: 'Nghiệm thu giai đoạn' }))
    const dialog = await screen.findByRole('dialog', { name: 'Nghiệm thu Giai đoạn 1?' })
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))

    const lines = await screen.findAllByText('Đang hợp nhất bàn giao Giai đoạn 1. Tiền được chuyển ngay khi hợp nhất xong.')
    expect(lines.length).toBeGreaterThan(0)
    expect(screen.queryByRole('link', { name: 'Kiểm tra từng tiêu chí' })).toBeNull()
  })

  it('links the preview with N-11 next to each step and its check steps (UX-DR33)', { timeout: 25_000 }, async () => {
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/handovers/h1': () =>
        json(report({ preview: { preview: { url: 'https://vht-preview.vercel.app', domain: 'vht-preview.vercel.app' } } })),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByRole('link', { name: /Mở bản xem thử/ })).toHaveAttribute('href', 'https://vht-preview.vercel.app')
    expect(screen.getByText('(vht-preview.vercel.app)')).toBeInTheDocument()
    expect(screen.getByText(/Trang này nằm ngoài vibefix \(vht-preview\.vercel\.app\)/)).toBeInTheDocument()
  })

  it('explains a Stale answer when a new change voided the handover meanwhile (UX-DR26)', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    window.sessionStorage.setItem('vibefix.check.h1', JSON.stringify({ step: 1, marks: { c1: { met: true, comment: '' } } }))
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/handovers/h1': () => json(report()),
      'POST /api/v1/handovers/h1/accept': () => json({ code: 'STALE', reason: 'HANDOVER_VOIDED' }, 409),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByText('Tiêu chí 2/2')).toBeInTheDocument()
    await user.click(screen.getByRole('radio', { name: 'Đạt' }))
    await user.click(screen.getByRole('button', { name: 'Tiếp' }))
    await user.click(await screen.findByRole('button', { name: 'Nghiệm thu giai đoạn' }))
    const dialog = await screen.findByRole('dialog', { name: 'Nghiệm thu Giai đoạn 1?' })
    await user.click(within(dialog).getByRole('button', { name: 'Xác nhận' }))

    expect(await screen.findByText('Bàn giao này đã bị hủy vì có thay đổi mới được lưu sau khi bàn giao.')).toBeInTheDocument()
  })

  it('offers only acceptance when no Revision Round is left', { timeout: 25_000 }, async () => {
    const user = userEvent.setup()
    window.sessionStorage.setItem(
      'vibefix.check.h1',
      JSON.stringify({ step: 2, marks: { c1: { met: true, comment: '' }, c2: { met: false, comment: '' } } }),
    )
    fakeBackend(signedIn('CUSTOMER'), {
      'GET /api/v1/handovers/h1': () => json(report({ roundsLeft: 0, decision: { releaseVnd: 1000001, revisionAllowed: false } })),
    })
    renderAt('/cong-viec/j1/ban-giao/h1/kiem-tra')

    expect(await screen.findByText('Giai đoạn này đã hết lượt sửa, nên chỉ còn nghiệm thu.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Yêu cầu sửa/ })).toBeNull()
    expect(screen.getByRole('button', { name: 'Nghiệm thu giai đoạn' })).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: 'Sửa' })[1])
    expect(await screen.findByText('Tiêu chí 2/2')).toBeInTheDocument()
  })
})
