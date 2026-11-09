<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import { useToast } from 'primevue/usetoast'
import { PageHeader, showErrorToast } from '@kinotic-ai/frontend-common'
import type { CustomerSummary } from '@/api/model'
import { staffCustomerService } from '@/api/services'
import { formatDate, planName } from '@/util/format'

/**
 * The customers whose license reached its anniversary with its figures unconfirmed. The license went on as it was;
 * staff follow up, and the flag clears once the customer confirms.
 */
const router = useRouter()
const toast = useToast()
const rows = ref<CustomerSummary[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    rows.value = await staffCustomerService.findRevenueUnconfirmed()
  } catch (err) {
    showErrorToast(toast, 'The unconfirmed revenue could not be loaded', err)
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <PageHeader title="Unconfirmed revenue"
                description="Licenses whose anniversary passed without the customer confirming its figures. Nothing stopped; follow up with the customer." />
    <DataTable :value="rows" :loading="loading" data-key="id" row-hover class="cursor-pointer"
               @row-click="router.push(`/customers/${$event.data.id}`)">
      <template #empty>No license is waiting on its figures.</template>
      <Column field="legalName" header="Customer" />
      <Column field="billingEmail" header="Billing contact" />
      <Column header="Plan"><template #body="{ data }">{{ planName(data.plan) }}</template></Column>
      <Column header="Next anniversary"><template #body="{ data }">{{ formatDate(data.termEnd) }}</template></Column>
    </DataTable>
  </div>
</template>
