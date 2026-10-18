import createClient from 'openapi-fetch'
import type { paths } from '../api/schema'

/**
 * The only way the SPA talks to the backend (AD-23): the client generated from the backend's
 * OpenAPI document. ESLint bans `fetch` and `openapi-fetch` everywhere else.
 */
export const apiClient = createClient<paths>({ baseUrl: '' })

/** The RFC 9457 Problem Details body of an error response; the SPA maps `code` and `reason` to Vietnamese. */
export interface Problem {
  status?: number
  code?: string
  reason?: string
  errors?: { field: string; code: string }[]
}

export class ApiError extends Error {
  readonly status: number
  readonly problem: Problem

  constructor(status: number, problem: Problem) {
    super(`API error ${status}${problem.code ? ` ${problem.code}` : ''}`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

/** Resolves to the response data, or rejects with an {@link ApiError} carrying the Problem Details. */
export async function unwrap<T>(
  request: Promise<{ data?: T; error?: unknown; response: Response }>,
): Promise<T> {
  const { data, error, response } = await request
  if (!response.ok || data === undefined) {
    throw new ApiError(response.status, isProblem(error) ? error : {})
  }
  return data
}

function isProblem(value: unknown): value is Problem {
  return typeof value === 'object' && value !== null
}
