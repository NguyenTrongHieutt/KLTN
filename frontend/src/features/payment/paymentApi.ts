import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { JOBS_QUERY_KEY } from '@/features/job/jobApi'
import { apiClient, unwrap } from '@/lib/apiClient'

export type JobFunding = components['schemas']['JobFundingView']
export type FundingMilestone = components['schemas']['FundingMilestoneView']
export type PaymentOrderSummary = components['schemas']['PaymentOrderView']
export type PaymentOrder = components['schemas']['PaymentOrderDetailView']

const fundingKey = (jobId: string) => ['payment', 'funding', jobId] as const

/** An order no longer waits for VNPay (AD-14). */
export function isFinal(status: string): boolean {
  return status !== 'OPEN'
}

/** JOB-7 for the Customer: the Milestones to fund and the orders (FR-41). */
export function useJobFunding(jobId: string) {
  return useQuery({
    queryKey: fundingKey(jobId),
    queryFn: () => unwrap(apiClient.GET('/api/v1/jobs/{jobId}/funding', { params: { path: { jobId } } })),
    retry: false,
  })
}

/** "Thanh toán": the order and the sandbox page to open in the same tab (AD-14). */
export function useCreatePaymentOrder(jobId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ milestoneId, version }: { milestoneId: string; version: number }) =>
      unwrap(
        apiClient.POST('/api/v1/jobs/{jobId}/milestones/{milestoneId}/payment-orders', {
          params: { path: { jobId, milestoneId } },
          body: { version },
        }),
      ),
    onError: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: fundingKey(jobId) }),
        queryClient.invalidateQueries({ queryKey: [...JOBS_QUERY_KEY, 'detail', jobId] }),
      ]),
  })
}

/** CUS-9 polls every 2 s for 2 minutes, then every 15 s, until the order is final (UX-DR25). */
export const FAST_POLL_MS = 2_000
export const SLOW_POLL_MS = 15_000
export const FAST_POLL_FOR_MS = 120_000

/** CUS-9: one order's status, read from the server only. */
export function usePaymentOrder(paymentOrderId: string, startedAt: number) {
  return useQuery({
    queryKey: ['payment', 'order', paymentOrderId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/payment-orders/{paymentOrderId}', { params: { path: { paymentOrderId } } })),
    retry: false,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      if (status !== undefined && isFinal(status)) {
        return false
      }
      return Date.now() - startedAt < FAST_POLL_FOR_MS ? FAST_POLL_MS : SLOW_POLL_MS
    },
  })
}

/** N-13's help panel: the sandbox test card from configuration. */
export function useTestCard(enabled: boolean) {
  return useQuery({
    queryKey: ['payment', 'test-card'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/payment/test-card')),
    enabled,
    staleTime: Infinity,
  })
}
