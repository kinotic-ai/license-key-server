import './style.css'
import 'primeicons/primeicons.css'
import '@kinotic-ai/frontend-common/style.css'
import '@fontsource-variable/jetbrains-mono'
import { createKinoticApp } from '@kinotic-ai/frontend-common'

import App from './App.vue'
import publicLoginArt from './assets/public-login-background.svg'
import publicLoginBrandMark from './assets/public-login-page-kinotic-logo.svg'
import staffLoginArt from './assets/staff-login-background.svg'
import staffLoginBrandMark from './assets/staff-login-page-kinotic-logo.svg'
import { createLicenseRouter } from './router'
import { loadSite } from './states/site'
import { SESSION_STATE } from './states/LicenseSessionState'

// The one app serves two hostnames; which one this page was served on decides its routes and its brand color:
// the customers' site wears the portal's mint and the staff dashboard the system console's red
loadSite().then(site => {
    const staff = site === 'STAFF'
    // style.css keys the staff dashboard's auth page colors on this class
    document.documentElement.classList.toggle('staff-site', staff)
    createKinoticApp({
        root: App,
        router: createLicenseRouter(site),
        sessionState: SESSION_STATE,
        authPageBranding: staff ? { art: staffLoginArt, darkBrandMark: staffLoginBrandMark }
                                : { art: publicLoginArt, darkBrandMark: publicLoginBrandMark }
    }).mount('#app')
}).catch(err => {
    console.error('The site could not be loaded', err)
    document.getElementById('app')!.textContent = 'This page could not be loaded. Reload it in a moment.'
})
