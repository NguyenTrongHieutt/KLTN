/**
 * CUS-5's rotation guidance (FR-8, UX-DR30): each credential rule of `scanning/rules.yaml` belongs to
 * a provider group, whose texts are in `vi.json` (`screen.product.finding.guidance`) and whose key
 * page is linked as an External link. A group without a key page names where to look instead.
 */
export type GuidanceGroup =
  | 'github'
  | 'supabase'
  | 'supabaseJwt'
  | 'googleKey'
  | 'googleServiceAccount'
  | 'aws'
  | 'stripe'
  | 'vnpay'
  | 'momo'
  | 'zalopay'
  | 'openai'
  | 'anthropic'
  | 'sendgrid'
  | 'resend'
  | 'twilio'
  | 'vercel'
  | 'netlify'
  | 'database'
  | 'privateKey'
  | 'jwtSecret'
  | 'envSecret'

const RULE_GROUPS: Record<string, GuidanceGroup> = {
  'github-pat': 'github',
  'github-fine-grained-pat': 'github',
  'github-oauth': 'github',
  'github-app-token': 'github',
  'github-refresh-token': 'github',
  'gcp-service-account': 'googleServiceAccount',
  'private-key': 'privateKey',
  'supabase-service-role-key': 'supabase',
  'supabase-secret-key': 'supabase',
  'supabase-jwt-secret': 'supabaseJwt',
  'gcp-api-key': 'googleKey',
  'aws-access-key-id': 'aws',
  'aws-secret-access-key': 'aws',
  'stripe-secret-key': 'stripe',
  'stripe-restricted-key': 'stripe',
  'vnpay-hash-secret': 'vnpay',
  'momo-secret-key': 'momo',
  'zalopay-key': 'zalopay',
  'openai-api-key': 'openai',
  'anthropic-api-key': 'anthropic',
  'sendgrid-api-token': 'sendgrid',
  'resend-api-key': 'resend',
  'twilio-api-key': 'twilio',
  'twilio-auth-token': 'twilio',
  'vercel-token': 'vercel',
  'netlify-access-token': 'netlify',
  'netlify-token': 'netlify',
  'database-connection-string': 'database',
  'jwt-signing-secret': 'jwtSecret',
  'dotenv-secret': 'envSecret',
}

/** Each provider's page for creating and revoking keys. */
export const KEY_PAGES: Partial<Record<GuidanceGroup, string>> = {
  github: 'https://github.com/settings/tokens',
  supabase: 'https://supabase.com/dashboard/project/_/settings/api-keys',
  supabaseJwt: 'https://supabase.com/dashboard/project/_/settings/jwt',
  googleKey: 'https://console.cloud.google.com/apis/credentials',
  googleServiceAccount: 'https://console.cloud.google.com/iam-admin/serviceaccounts',
  aws: 'https://console.aws.amazon.com/iam/home#/security_credentials',
  stripe: 'https://dashboard.stripe.com/apikeys',
  vnpay: 'https://vnpay.vn',
  momo: 'https://business.momo.vn',
  zalopay: 'https://zalopay.vn',
  openai: 'https://platform.openai.com/api-keys',
  anthropic: 'https://console.anthropic.com/settings/keys',
  sendgrid: 'https://app.sendgrid.com/settings/api_keys',
  resend: 'https://resend.com/api-keys',
  twilio: 'https://console.twilio.com',
  vercel: 'https://vercel.com/account/tokens',
  netlify: 'https://app.netlify.com/user/applications',
}

/** The group of a credential rule; an unknown rule gets the generic steps of a `.env` value. */
export function guidanceGroup(ruleId: string): GuidanceGroup {
  return RULE_GROUPS[ruleId] ?? 'envSecret'
}
