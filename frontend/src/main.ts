import './style.css'
import 'primeicons/primeicons.css'
import '@kinotic-ai/frontend-common/style.css'
import '@fontsource-variable/jetbrains-mono'
import { createKinoticApp } from '@kinotic-ai/frontend-common'

import App from './App.vue'
import loginArt from './assets/login-background.svg'
import loginBrandMark from './assets/login-page-kinotic-logo.svg'
import { createLicenseRouter } from './router'
import { loadSite } from './states/site'
import { SESSION_STATE } from './states/LicenseSessionState'

// The one app serves two hostnames; which one this page was served on decides its routes
loadSite().then(site => {
    createKinoticApp({
        root: App,
        router: createLicenseRouter(site),
        sessionState: SESSION_STATE,
        authPageBranding: { art: loginArt, darkBrandMark: loginBrandMark }
    }).mount('#app')
}).catch(err => {
    console.error('The site could not be loaded', err)
    document.getElementById('app')!.textContent = 'This page could not be loaded. Reload it in a moment.'
})
