/**
 * Tìm việc's keyword in its URL, shared by the page and the top bar's search: from 768 px the top bar's field is the
 * one search of the app, on Tìm việc too (the layout of 2026-11-24), so both read and write the same parameter.
 */
export const FIND_WORK_PATH = '/tim-viec'

/** The keyword's parameter. */
export const KEYWORD_PARAM = 'q'

/** The page's parameter, which a new keyword starts over. */
export const PAGE_PARAM = 'trang'

/** The Search bar's limit (EXPERIENCE.md, Search bar). */
export const MAX_KEYWORD = 100

/** From this width the top bar carries the search, and Tìm việc shows no Search bar of its own. */
export const TOP_SEARCH_QUERY = '(min-width: 768px)'
