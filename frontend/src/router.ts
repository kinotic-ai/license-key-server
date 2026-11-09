import { createRouter, createWebHistory, type RouteRecordRaw, type Router } from 'vue-router'
import { Building2, Gift, Handshake, Inbox, TriangleAlert } from '@lucide/vue'
import type { SidebarItemMeta } from '@kinotic-ai/frontend-common'
import type { Site } from '@/api/model'

function dashboardItem(label: string, icon: SidebarItemMeta['icon'], order: number): SidebarItemMeta {
    return { group: 'dashboard', label, icon, order }
}

/** The public site: sign-up and sign-in, and for a signed-in customer the purchase, free request, design partner application and account. */
const PUBLIC_ROUTES: RouteRecordRaw[] = [
    {
        path: '/',
        component: () => import('./layouts/PublicLayout.vue'),
        children: [
            { path: '', redirect: '/account' },
            { path: 'buy', component: () => import('./pages/public/BuyPage.vue') },
            { path: 'buy/success', component: () => import('./pages/public/BuySuccessPage.vue') },
            { path: 'free', component: () => import('./pages/public/FreeRequestPage.vue') },
            { path: 'account', component: () => import('./pages/public/AccountPage.vue') },
            { path: 'design-partner', component: () => import('./pages/public/DesignPartnerPage.vue') },
            { path: 'design-partner/checkout', component: () => import('./pages/public/DesignPartnerCheckoutPage.vue') }
        ]
    },
    { path: '/login', component: () => import('./pages/public/LoginPage.vue'), meta: { authenticationRequired: false } },
    { path: '/signup', component: () => import('./pages/public/SignUpPage.vue'), meta: { authenticationRequired: false } },
    { path: '/:catchAll(.*)', redirect: '/' }
]

/** The staff site: the customer list, the free request and design partner queues, and sign-in through the staff provider. */
const STAFF_ROUTES: RouteRecordRaw[] = [
    {
        path: '/',
        component: () => import('./layouts/StaffLayout.vue'),
        redirect: '/customers',
        children: [
            {
                path: 'customers',
                component: () => import('./pages/staff/CustomersPage.vue'),
                meta: { sidebar: dashboardItem('Customers', Building2, 10) }
            },
            {
                path: 'customers/:customerId',
                component: () => import('./pages/staff/CustomerPage.vue'),
                props: true,
                meta: { sidebarGroup: 'dashboard' }
            },
            {
                path: 'design-partner-applications',
                component: () => import('./pages/staff/DesignPartnerApplicationsPage.vue'),
                meta: { sidebar: dashboardItem('Design partner applications', Inbox, 40) }
            },
            {
                path: 'design-partners',
                component: () => import('./pages/staff/DesignPartnersPage.vue'),
                meta: { sidebar: dashboardItem('Design partners', Handshake, 50) }
            },
            {
                path: 'unconfirmed-revenue',
                component: () => import('./pages/staff/UnconfirmedRevenuePage.vue'),
                meta: { sidebar: dashboardItem('Unconfirmed revenue', TriangleAlert, 30) }
            },
            {
                path: 'free-requests',
                component: () => import('./pages/staff/FreeRequestsPage.vue'),
                meta: { sidebar: dashboardItem('Free license requests', Gift, 20) }
            }
        ]
    },
    { path: '/login', component: () => import('./pages/staff/StaffLoginPage.vue'), meta: { authenticationRequired: false } },
    { path: '/:catchAll(.*)', redirect: '/customers' }
]

export function createLicenseRouter(site: Site): Router {
    return createRouter({
        history: createWebHistory(),
        routes: site === 'STAFF' ? STAFF_ROUTES : PUBLIC_ROUTES
    })
}
