import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { StageStrip } from './StageStrip'
import { currentStage } from './stages'

describe('the 7-stage strip (EXPERIENCE.md, UX changes of 2026-11-23)', () => {
  it('maps each Job state to its current stage', () => {
    expect(currentStage('DRAFT')).toBe(1)
    expect(currentStage('OPEN')).toBe(3)
    expect(currentStage('SELECTING')).toBe(3)
    expect(currentStage('AGREEMENT')).toBe(3)
    expect(currentStage('STARTING')).toBe(4)
    expect(currentStage('ACTIVE')).toBe(5)
    expect(currentStage('CLOSING')).toBe(7)
    expect(currentStage('CLOSED')).toBe(8)
    expect(currentStage('CANCELLED')).toBeUndefined()
  })

  it('marks the current stage and names each stage for screen readers', () => {
    render(<StageStrip state="ACTIVE" />)

    const strip = screen.getByRole('navigation', { name: 'Tiến trình công việc' })
    expect(strip.querySelector('[aria-current="step"]')).toHaveTextContent('Bước 5, Làm và theo dõi: đang ở bước này')
    expect(strip).toHaveTextContent('Bước 4, Lập kế hoạch: đã xong')
    expect(strip).toHaveTextContent('Bước 6, Bàn giao và nghiệm thu: chưa tới')
  })

  it('shows nothing for a cancelled Job', () => {
    const { container } = render(<StageStrip state="CANCELLED" />)

    expect(container).toBeEmptyDOMElement()
  })
})
