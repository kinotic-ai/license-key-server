import { reactive } from 'vue'
import { fetchSite } from '@/api/rest'
import type { Site, SiteInfo } from '@/api/model'

/** Which site this page was served on, the terms version it shows and the sign-in providers it offers; loaded before the app is created. */
export const SITE = reactive<SiteInfo>({ site: 'PUBLIC', termsVersion: '', signInProviders: [] })

export async function loadSite(): Promise<Site> {
    const info = await fetchSite()
    SITE.site = info.site
    SITE.termsVersion = info.termsVersion
    SITE.signInProviders = info.signInProviders
    return info.site
}
