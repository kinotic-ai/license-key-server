import { createRouter, createWebHistory, type RouteRecordRaw, type Router } from 'vue-router'
import { Building2, Gift } from '@lucide/vue'
import type { SidebarItemMeta } from '@kinotic-ai/frontend-common'
import type { Site } from '@/api/model'

function dashboardItem(label: string, icon: SidebarItemMeta['icon'], order: number): SidebarItemMeta {
    return { group: 'dashboard', label, icon, order }
}

/** The public site: the plans, sign-up and sign-in, and for a signed-in customer the purchase, free request and account. */
const PUBLIC_ROUTES: RouteRecordRaw[] = [
    {
        path: '/',
        component: () => import('./layouts/PublicLayout.vue'),
        children: [
            { path: '', component: () => import('./pages/public/PlansPage.vue'), meta: { authenticationRequired: false } },
            { path: 'buy', component: () => import('./pages/public/BuyPage.vue') },
            { path: 'buy/success', component: () => import('./pages/public/BuySuccessPage.vue') },
            { path: 'free', component: () => import('./pages/public/FreeRequestPage.vue') },
            { path: 'account', component: () => import('./pages/public/AccountPage.vue') }
        ]
    },
    { path: '/login', component: () => import('./pages/public/LoginPage.vue'), meta: { authenticationRequired: false } },
    { path: '/signup', component: () => import('./pages/public/SignUpPage.vue'), meta: { authenticationRequired: false } },
    { path: '/:catchAll(.*)', redirect: '/' }
]

/** The staff site: the customer list, the free request queue and sign-in through the staff provider. */
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
