<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { FunctionalIterablePage, type IterablePage, type Pageable } from '@kinotic-ai/core'
import {
  CrudTable,
  FormDrawer,
  PageHeader,
  TimePill,
  pageNumberOf,
  showErrorToast,
  useCrudTablePage,
  type CrudHeader,
  type DescriptiveIdentifiable
} from '@kinotic-ai/frontend-common'
import type { CustomerSummary } from '@/api/model'
import { staffCustomerService } from '@/api/services'
import LicenseStatusTag from '@/components/LicenseStatusTag.vue'
import { formatDate, planName } from '@/util/format'

/** Every customer with its plan, status, billing period, term, last payment and latest key; and the signing public key. */
const router = useRouter()
const toast = useToast()

const headers: CrudHeader[] = [
  { field: 'legalName', header: 'Customer', sortable: true },
  { field: 'plan', header: 'Plan' },
  { field: 'status', header: 'Status' },
  { field: 'currentPeriodEnd', header: 'Period ends' },
  { field: 'termEnd', header: 'Term ends' },
  { field: 'lastPaymentAt', header: 'Last payment' },
  { field: 'latestKey', header: 'Latest key' }
]

type Row = DescriptiveIdentifiable & CustomerSummary

// the list is small, so it is fetched whole and paged and searched here
async function load(pageable: Pageable, searchText: string | null): Promise<IterablePage<DescriptiveIdentifiable>> {
  const needle = searchText?.trim().toLowerCase()
  const rows: Row[] = (await staffCustomerService.findCustomerSummaries())
      .map(summary => ({ ...summary, name: summary.legalName }))
      .filter(row => !needle || [row.legalName, row.billingEmail ?? ''].some(field => field.toLowerCase().includes(needle)))
  const start = pageNumberOf(pageable) * pageable.pageSize
  const page = { content: rows.slice(start, start + pageable.pageSize), totalElements: rows.length, cursor: undefined }
  return new FunctionalIterablePage(pageable, page, (next: Pageable) => load(next, searchText))
}

const { tableSearch, dataSource } = useCrudTablePage(load)

// the signing public key, shown on request so staff can check it against the key Kinotic trusts
const signingPublicKey = ref<string | null>(null)
const loadingSigningKey = ref(false)

async function showSigningKey() {
  loadingSigningKey.value = true
  try {
    signingPublicKey.value = await staffCustomerService.getSigningPublicKey()
  } catch (err) {
    showErrorToast(toast, 'The signing key could not be read', err, { life: 8000 })
  } finally {
    loadingSigningKey.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="Customers" description="Every licensee, with the state of its newest license and the key it holds.">
      <template #actions>
        <Button label="Signing key" icon="pi pi-key" severity="secondary" outlined :loading="loadingSigningKey" @click="showSigningKey" />
      </template>
    </PageHeader>
    <CrudTable ref="crudTable" :headers="headers" :data-source="dataSource" :search="tableSearch" :is-show-add-new="false"
               empty-state-text="No customers yet" @update:search="tableSearch = $event"
               @on-row-click="router.push(`/customers/${$event.id}`)">
      <template #item.plan="{ item }">{{ planName(item.plan) }}</template>
      <template #item.status="{ item }"><LicenseStatusTag :status="item.status" /></template>
      <template #item.currentPeriodEnd="{ item }"><TimePill :date="item.currentPeriodEnd" /></template>
      <template #item.termEnd="{ item }"><TimePill :date="item.termEnd" /></template>
      <template #item.lastPaymentAt="{ item }"><TimePill :date="item.lastPaymentAt" /></template>
      <template #item.latestKey="{ item }">
        <span v-if="item.latestKeyJti" class="font-mono text-xs">{{ item.latestKeyJti.slice(0, 8) }}… <span class="app-muted-text">until {{ formatDate(item.latestKeyExpiresAt) }}</span></span>
        <span v-else class="app-muted-text">—</span>
      </template>
    </CrudTable>

    <FormDrawer :visible="signingPublicKey !== null" title="Signing public key"
                description="The public key every license key is signed with. It must equal TRUSTED_PUBLIC_KEY in Kinotic's LicenseVerifier, or Kinotic refuses every key this server issues."
                @close="signingPublicKey = null">
      <pre class="app-card-surface overflow-x-auto whitespace-pre-wrap break-all rounded-lg border p-3 font-mono text-xs">{{ signingPublicKey }}</pre>
      <template #footer>
        <Button type="button" label="Done" @click="signingPublicKey = null" />
      </template>
    </FormDrawer>
  </div>
</template>
