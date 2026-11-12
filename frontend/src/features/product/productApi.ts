import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Product = components['schemas']['ProductView']
export type SourceApp = components['schemas']['SourceAppView']
export type RepositoryCheck = components['schemas']['RepositoryCheckView']
export type WorkflowFile = components['schemas']['WorkflowFileView']
export type ProductRegistration = components['schemas']['ProductRegistrationRequest']
export type Tool = ProductRegistration['tools'][number]
/** A Product's recorded Source App and workflow status (Story 23.5c): the Workflow status badge. */
export type SourceStatus = Product['sourceStatus']
export type SourceCheck = components['schemas']['SourceCheckView']

export const PRODUCTS_QUERY_KEY = ['product'] as const

/** CUS-3 step 2: a fresh Source App install link for the pasted repository, with its single-use state. */
export function useSourceAppInstallLink() {
  return useMutation({
    mutationFn: (url: string) =>
      unwrap(apiClient.POST('/api/v1/products/source-app/install-link', { body: { url } })),
  })
}

/** CUS-3 step 3: the published `vibefix.yml` the Workflow setup card prefills, copies, and downloads (AD-45). */
export function useWorkflowFile() {
  return useQuery({
    queryKey: [...PRODUCTS_QUERY_KEY, 'workflow-file'],
    queryFn: () => unwrap(apiClient.GET('/api/v1/products/workflow-file')),
    staleTime: Infinity,
  })
}

/** CUS-3 step 3's "Kiểm tra": the default branch's `vibefix.yml`, read through the Source App. */
export function useWorkflowCheck() {
  return useMutation({
    mutationFn: (url: string) => unwrap(apiClient.POST('/api/v1/products/workflow-check', { body: { url } })),
  })
}

/** CUS-2: the Customer's Products. */
export function useProducts() {
  return useQuery({ queryKey: [...PRODUCTS_QUERY_KEY, 'list'], queryFn: () => unwrap(apiClient.GET('/api/v1/products')) })
}

/** CUS-4: one Product. */
export function useProduct(productId: string | undefined) {
  return useQuery({
    queryKey: [...PRODUCTS_QUERY_KEY, 'detail', productId],
    queryFn: () =>
      unwrap(apiClient.GET('/api/v1/products/{productId}', { params: { path: { productId: productId ?? '' } } })),
    enabled: productId !== undefined,
    retry: false,
  })
}

/**
 * CUS-4's "Kiểm tra lại" (Story 23.5c): the Source App binding and the workflow read again and recorded; CUS-4 and
 * CUS-2 then read the recorded status again.
 */
export function useSourceCheck(productId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      unwrap(apiClient.POST('/api/v1/products/{productId}/source-check', { params: { path: { productId } } })),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: [...PRODUCTS_QUERY_KEY, 'detail', productId] }),
        queryClient.invalidateQueries({ queryKey: [...PRODUCTS_QUERY_KEY, 'list'] }),
      ]),
  })
}

/** CUS-4 "Xóa sản phẩm", after the destructive Confirmation dialog. */
export function useDeleteProduct(productId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () =>
      unwrap(apiClient.DELETE('/api/v1/products/{productId}', { params: { path: { productId } } })),
    onSuccess: () => queryClient.removeQueries({ queryKey: [...PRODUCTS_QUERY_KEY, 'detail', productId] }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: [...PRODUCTS_QUERY_KEY, 'list'] }),
  })
}

/** CUS-3 step 2's "Kiểm tra": the Source App, then every repository check through it. */
export function useRepositoryCheck() {
  return useMutation({
    mutationFn: (url: string) => unwrap(apiClient.POST('/api/v1/products/repository-check', { body: { url } })),
  })
}

/** CUS-3 step 5: "Đăng ký sản phẩm"; the Product is registered at once, with no scan (FR-7). */
export function useRegisterProduct() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ProductRegistration) => unwrap(apiClient.POST('/api/v1/products', { body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PRODUCTS_QUERY_KEY }),
  })
}
