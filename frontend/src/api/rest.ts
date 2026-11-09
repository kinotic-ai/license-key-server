import { apiUrl, readAuthError } from '@kinotic-ai/frontend-common'
import type { SiteInfo } from './model'

/** What a page reads before it signs in, and the customer sign-in and sign-up routes. */

/** A refused request, with the HTTP status the server answered. */
export class ApiError extends Error {
    constructor(message: string, readonly status: number) {
        super(message)
    }
}

async function getJson<T>(path: string): Promise<T> {
    const res = await fetch(apiUrl(path), { credentials: 'include' })
    if (!res.ok) {
        throw new ApiError(await readAuthError(res, `${path} failed with ${res.status}`), res.status)
    }
    return res.json()
}

async function postJson<T>(path: string, body: unknown, fallback: string): Promise<T | null> {
    const res = await fetch(apiUrl(path), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(body)
    })
    if (!res.ok) {
        throw new ApiError(await readAuthError(res, fallback), res.status)
    }
    return res.status === 204 || res.status === 202 ? null : res.json()
}

export function fetchSite(): Promise<SiteInfo> {
    return getJson('/api/site')
}

/** Asks for a code; every address is sent one, so the answer says nothing about whether it has an account. */
export async function requestSignInCode(email: string): Promise<void> {
    await postJson('/api/auth/code/request', { email }, 'The code could not be sent')
}

/**
 * Confirms the address with the emailed code. When it belongs to an account the page is now signed in to it;
 * otherwise the page may sign up with it through {@link signUp}.
 */
export async function verifySignInCode(email: string, code: string): Promise<{ account: boolean }> {
    const result = await postJson<{ account: boolean }>('/api/auth/code/verify', { email, code }, 'The code is not valid')
    return result!
}

/** Creates the account of the address the page confirmed, and signs the page in to it. */
export async function signUp(organization: string, contactName: string): Promise<void> {
    await postJson('/api/auth/signup', { organization, contactName }, 'The account could not be created')
}
