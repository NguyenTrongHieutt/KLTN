import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Product = components['schemas']['ProductView']
export type Scan = components['schemas']['ScanView']
export type Finding = components['schemas']['FindingView']
export type RepositoryCheck = components['schemas']['RepositoryCheckView']
export type ProductRegistration = components['schemas']['ProductRegistrationRequest']
export type Tool = ProductRegistration['tools'][number]

export const PRODUCTS_QUERY_KEY = ['product'] as const

/** CUS-4 polls every 5 s while a scan is waiting, running, or about to be tried again (UX-DR21). */
export const SCAN_POLL_MS = 5_000

/** Whether the scan still has an attempt ahead of it. */
export function scanActive(scan: Scan | undefined): boolean {
  return scan?.status === 'PENDING' || scan?.status === 'RUNNING' || scan?.status === 'RETRYING'
}

/** CUS-2: the Customer's Products. */
export function useProducts() {
  return useQuery({ queryKey: [...PRODUCTS_QUERY_KEY, 'list'], queryFn: () => unwrap(apiClient.GET('/api/v1/products')) })
}

/** CUS-4: one Product, polled while its scan is active. */
export function useProduct(productId: string | undefined) {
  return useQuery({
    queryKey: [...PRODUCTS_QUERY_KEY, 'detail', productId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/products/{productId}', { params: { path: { productId: productId ?? '' } } })),
    enabled: productId !== undefined,
    retry: false,
    refetchInterval: (query) => (scanActive(query.state.data?.scan) ? SCAN_POLL_MS : false),
  })
}

/** CUS-4 "Vấn đề phát hiện": the open Findings, read again whenever their count changes. */
export function useFindings(product: Product) {
  return useQuery({
    queryKey: [...PRODUCTS_QUERY_KEY, 'findings', product.id, product.openFindings, product.scan?.id],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/products/{productId}/findings', { params: { path: { productId: product.id } } })),
    enabled: product.openFindings > 0,
  })
}

/** CUS-3 step 2: every repository check, read with the Customer's own GitHub sign-in. */
export function useRepositoryCheck() {
  return useMutation({
    mutationFn: (url: string) => unwrap(apiClient.POST('/api/v1/products/repository-check', { body: { url } })),
  })
}

/** CUS-3 step 4: "Bắt đầu kiểm tra". */
export function useRegisterProduct() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ProductRegistration) => unwrap(apiClient.POST('/api/v1/products', { body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PRODUCTS_QUERY_KEY }),
  })
}
