import './style.css'
import 'primeicons/primeicons.css'
import '@kinotic-ai/frontend-common/style.css'
import '@fontsource-variable/jetbrains-mono'
import { createKinoticApp } from '@kinotic-ai/frontend-common'

import App from './App.vue'
import { createLicenseRouter } from './router'
import { loadSite } from './states/site'
import { SESSION_STATE } from './states/LicenseSessionState'

// The one app serves two hostnames; which one this page was served on decides its routes
loadSite().then(site => {
    createKinoticApp({ root: App, router: createLicenseRouter(site), sessionState: SESSION_STATE }).mount('#app')
})
