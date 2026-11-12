import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { components } from '@/api/schema'
import { apiClient, unwrap } from '@/lib/apiClient'

export type Product = components['schemas']['ProductView']
export type RepositoryCheck = components['schemas']['RepositoryCheckView']
export type ProductRegistration = components['schemas']['ProductRegistrationRequest']
export type Tool = ProductRegistration['tools'][number]

export const PRODUCTS_QUERY_KEY = ['product'] as const

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

/** CUS-3 step 2: every repository check, read with the Customer's own GitHub sign-in. */
export function useRepositoryCheck() {
  return useMutation({
    mutationFn: (url: string) => unwrap(apiClient.POST('/api/v1/products/repository-check', { body: { url } })),
  })
}

/** CUS-3 step 4: "Đăng ký sản phẩm"; the Product is registered at once, with no scan (FR-7). */
export function useRegisterProduct() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ProductRegistration) => unwrap(apiClient.POST('/api/v1/products', { body })),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PRODUCTS_QUERY_KEY }),
  })
}
