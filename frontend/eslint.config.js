import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

// AD-21: the SPA never renders raw HTML: not as a JSX attribute, not through a props object, and
// not through the DOM's own HTML sinks.
const RAW_HTML = 'Raw HTML is banned (AD-21); render text, or Markdown through react-markdown.'
const noRawHtml = [
  { selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']", message: RAW_HTML },
  { selector: "Property[key.name='dangerouslySetInnerHTML']", message: RAW_HTML },
  { selector: "Property[key.value='dangerouslySetInnerHTML']", message: RAW_HTML },
  {
    selector: "Property[computed=true][key.type='TemplateLiteral'][key.quasis.0.value.cooked='dangerouslySetInnerHTML']",
    message: RAW_HTML,
  },
  { selector: "MemberExpression[property.name='dangerouslySetInnerHTML']", message: RAW_HTML },
  { selector: 'AssignmentExpression[left.property.name=/^(innerHTML|outerHTML)$/]', message: RAW_HTML },
  {
    selector: 'CallExpression[callee.property.name=/^(insertAdjacentHTML|createContextualFragment)$/]',
    message: RAW_HTML,
  },
  { selector: "CallExpression[callee.object.name='document'][callee.property.name=/^(write|writeln)$/]", message: RAW_HTML },
]

// AD-23: the backend is called only through the generated client in src/lib (and AD-27: no
// WebSocket or server-sent events).
const API_ONLY = 'Call the backend through src/lib/apiClient (AD-23).'
const noDirectHttp = {
  'no-restricted-globals': [
    'error',
    ...['fetch', 'XMLHttpRequest', 'EventSource', 'WebSocket'].map((name) => ({ name, message: API_ONLY })),
  ],
  'no-restricted-properties': [
    'error',
    ...['window', 'globalThis', 'self'].map((object) => ({ object, property: 'fetch', message: API_ONLY })),
    { object: 'navigator', property: 'sendBeacon', message: API_ONLY },
  ],
  'no-restricted-imports': [
    'error',
    { paths: [{ name: 'openapi-fetch', message: API_ONLY }], patterns: [{ group: ['openapi-fetch/*'], message: API_ONLY }] },
  ],
}

export default defineConfig([
  // The client types are generated from the backend's OpenAPI document and never edited by hand.
  // Anything else placed in src/api is linted like the rest of src.
  globalIgnores(['dist', 'src/api/schema.d.ts']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
  },
  {
    files: ['src/**/*.{ts,tsx,js,jsx,mjs,cjs}'],
    languageOptions: {
      parserOptions: { ecmaFeatures: { jsx: true } },
    },
    rules: {
      'no-restricted-syntax': ['error', ...noRawHtml],
      ...noDirectHttp,
    },
  },
  {
    files: ['src/lib/**/*.{ts,tsx,js,jsx,mjs,cjs}'],
    rules: {
      'no-restricted-globals': 'off',
      'no-restricted-properties': 'off',
      'no-restricted-imports': 'off',
    },
  },
  {
    files: ['scripts/**/*.mjs'],
    languageOptions: {
      globals: globals.node,
    },
  },
])
