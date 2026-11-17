import { createRouter, createWebHistory, type RouteRecordRaw, type Router } from 'vue-router'
import { Building2, Gift, Handshake, Inbox, KeyRound, ShoppingCart, TriangleAlert } from '@lucide/vue'
import type { SidebarItemMeta } from '@kinotic-ai/frontend-common'
import type { Site } from '@/api/model'

function dashboardItem(label: string, icon: SidebarItemMeta['icon'], order: number): SidebarItemMeta {
    return { group: 'dashboard', label, icon, order }
}

function accountItem(label: string, icon: SidebarItemMeta['icon'], order: number, section?: string): SidebarItemMeta {
    return { group: 'account', label, icon, order, section }
}

/** The public site: sign-up, sign-in and password reset, and for a signed-in customer the purchase, free request, design partner application and account. */
const PUBLIC_ROUTES: RouteRecordRaw[] = [
    {
        path: '/',
        component: () => import('./layouts/SiteLayout.vue'),
        children: [
            { path: '', redirect: '/account' },
            {
                path: 'account',
                component: () => import('./pages/public/AccountPage.vue'),
                meta: { sidebar: accountItem('Licenses', KeyRound, 10) }
            },
            {
                path: 'buy',
                component: () => import('./pages/public/BuyPage.vue'),
                meta: { sidebar: accountItem('Buy a plan', ShoppingCart, 20, 'Get a license') }
            },
            { path: 'buy/success', component: () => import('./pages/public/BuySuccessPage.vue'), meta: { sidebarGroup: 'account' } },
            {
                path: 'free',
                component: () => import('./pages/public/FreeRequestPage.vue'),
                meta: { sidebar: accountItem('Free license', Gift, 30, 'Get a license') }
            },
            {
                path: 'design-partner',
                component: () => import('./pages/public/DesignPartnerPage.vue'),
                meta: { sidebar: accountItem('Design partners', Handshake, 40, 'Get a license') }
            },
            {
                path: 'design-partner/checkout',
                component: () => import('./pages/public/DesignPartnerCheckoutPage.vue'),
                meta: { sidebarGroup: 'account' }
            }
        ]
    },
    { path: '/login', component: () => import('./pages/public/LoginPage.vue'), meta: { authenticationRequired: false } },
    { path: '/signup', component: () => import('./pages/public/SignUpPage.vue'), meta: { authenticationRequired: false } },
    { path: '/signup/complete', component: () => import('./pages/public/CompleteSignUpPage.vue'), meta: { authenticationRequired: false } },
    { path: '/password/forgot', component: () => import('./pages/public/ForgotPasswordPage.vue'), meta: { authenticationRequired: false } },
    { path: '/password/reset', component: () => import('./pages/public/ResetPasswordPage.vue'), meta: { authenticationRequired: false } },
    { path: '/:catchAll(.*)', redirect: '/' }
]

/** The staff site: the customer list, the free request and design partner queues, and sign-in through the staff provider. */
const STAFF_ROUTES: RouteRecordRaw[] = [
    {
        path: '/',
        component: () => import('./layouts/SiteLayout.vue'),
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
