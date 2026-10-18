import { describe, expect, it } from 'vitest'
import { ApiError } from './apiClient'
import { isNotFound, problemMessage } from './problemMessage'

describe('problemMessage (AD-23, UX-DR9)', () => {
  it('maps each code to its Vietnamese pattern', () => {
    expect(problemMessage(new ApiError(404, { code: 'NOT_FOUND' }))).toEqual({ text: 'Không tìm thấy nội dung này.' })
    expect(problemMessage(new ApiError(409, { code: 'STALE' })).text).toBe('Thông tin đã thay đổi trong lúc bạn xem.')
    expect(problemMessage(new ApiError(400, { code: 'VALIDATION', errors: [{ field: 'a', code: 'NotBlank' }, { field: 'b', code: 'Size' }] })).text)
      .toBe('Hãy kiểm tra lại 2 mục được đánh dấu.')
    expect(problemMessage(new ApiError(429, { code: 'RATE_LIMITED' }), { time: '30 giây' }).text)
      .toBe('Bạn thao tác hơi nhanh. Hãy thử lại sau 30 giây.')
  })

  it('adds the reason of the calling module', () => {
    const vi = { reason: { probe: { PROBE_GONE: 'tin này đã bị gỡ' } } }

    expect(problemMessage(new ApiError(422, { code: 'GUARD_FAILED', reason: 'PROBE_GONE' }), {
      module: 'probe',
      action: 'ứng tuyển',
      messages: vi,
    }).text).toBe('Chưa thể ứng tuyển: tin này đã bị gỡ.')
    expect(problemMessage(new ApiError(403, { code: 'FORBIDDEN', reason: 'PROBE_GONE' }), {
      module: 'probe',
      messages: vi,
    }).text).toBe('Bạn không thể làm việc này. Tin này đã bị gỡ.')
  })

  it('never shows a code: an unknown code or a 5xx is the unexpected message with its reference', () => {
    expect(problemMessage(new ApiError(500, { correlationId: '7f3c9a21-0d4e' }))).toEqual({
      text: 'Đã có lỗi. Hãy thử lại sau ít phút.',
      reference: 'Mã tham chiếu: 7f3c9a21-0d4e',
    })
    expect(problemMessage(new ApiError(418, { code: 'TEAPOT' }))).toEqual({ text: 'Đã có lỗi. Hãy thử lại sau ít phút.' })
    expect(problemMessage(new Error('network down'))).toEqual({ text: 'Đã có lỗi. Hãy thử lại sau ít phút.' })
  })

  it('says the session ended on a 401', () => {
    expect(problemMessage(new ApiError(401, {})).text).toBe('Phiên đăng nhập đã hết.')
  })

  it('recognizes a not-found answer, which also covers what the caller may not see', () => {
    expect(isNotFound(new ApiError(404, { code: 'NOT_FOUND' }))).toBe(true)
    expect(isNotFound(new ApiError(403, { code: 'FORBIDDEN' }))).toBe(false)
    expect(isNotFound(new Error('x'))).toBe(false)
  })
})
