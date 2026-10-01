import { api, type Me } from './api.ts'

/** 目前登入者（匿名時為空物件）。 */
export const session = { me: {} as Me, listeners: [] as (() => void)[] }

export async function refreshUser() {
  try { session.me = (await api.me()).user } catch { session.me = {} }
  for (const l of session.listeners) l()
}
