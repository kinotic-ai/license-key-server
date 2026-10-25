<script setup lang="ts">
import { ref } from 'vue'
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
import type { FreeLicenseRequest } from '@/api/model'
import { staffFreeLicenseRequestService } from '@/api/services'
import { formatDate, formatMoney } from '@/util/format'

/** The queue of free license requests, each reviewed in a drawer showing what the organization certified. */
const toast = useToast()

const headers: CrudHeader[] = [
  { field: 'organization', header: 'Organization', sortable: true },
  { field: 'contact', header: 'Contact' },
  { field: 'annualRevenue', header: 'Annual revenue' },
  { field: 'capitalRaised', header: 'Capital raised' },
  { field: 'createdAt', header: 'Requested' }
]

type Row = DescriptiveIdentifiable & FreeLicenseRequest

async function load(pageable: Pageable, searchText: string | null): Promise<IterablePage<DescriptiveIdentifiable>> {
  const needle = searchText?.trim().toLowerCase()
  const rows: Row[] = (await staffFreeLicenseRequestService.findPending())
      .map(request => ({ ...request, name: request.organization }))
      .filter(row => !needle || [row.organization, row.contactName, row.contactEmail].some(field => field.toLowerCase().includes(needle)))
  const start = pageNumberOf(pageable) * pageable.pageSize
  const page = { content: rows.slice(start, start + pageable.pageSize), totalElements: rows.length, cursor: undefined }
  return new FunctionalIterablePage(pageable, page, (next: Pageable) => load(next, searchText))
}

const { tableSearch, dataSource, refreshTable } = useCrudTablePage(load)

const reviewing = ref<FreeLicenseRequest | null>(null)
const deciding = ref(false)

async function decide(approve: boolean) {
  if (!reviewing.value) {
    return
  }
  deciding.value = true
  try {
    if (approve) {
      await staffFreeLicenseRequestService.approve(reviewing.value.id)
      toast.add({ severity: 'success', summary: 'Request approved', detail: 'The key was issued and emailed.', life: 5000 })
    } else {
      await staffFreeLicenseRequestService.reject(reviewing.value.id)
      toast.add({ severity: 'success', summary: 'Request rejected', detail: 'The contact was told.', life: 5000 })
    }
    reviewing.value = null
    refreshTable()
  } catch (err) {
    showErrorToast(toast, 'The request could not be reviewed', err, { life: 8000 })
  } finally {
    deciding.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="Free license requests" description="Requests awaiting review, oldest first. Approving issues the key; a renewal request starts the license's next term." />
    <CrudTable ref="crudTable" :headers="headers" :data-source="dataSource" :search="tableSearch" :is-show-add-new="false"
               empty-state-text="No requests waiting" @update:search="tableSearch = $event" @on-row-click="reviewing = $event">
      <template #item.contact="{ item }">{{ item.contactName }} <span class="app-muted-text">&lt;{{ item.contactEmail }}&gt;</span></template>
      <template #item.annualRevenue="{ item }">{{ formatMoney(item.annualRevenue, 'usd') }}</template>
      <template #item.capitalRaised="{ item }">{{ formatMoney(item.capitalRaised, 'usd') }}</template>
      <template #item.createdAt="{ item }"><TimePill :date="item.createdAt" /></template>
    </CrudTable>

    <FormDrawer :visible="reviewing !== null" :title="reviewing?.organization ?? ''" description="What the organization certified about itself." @close="reviewing = null">
      <dl v-if="reviewing" class="flex flex-col gap-4 text-sm">
        <div><dt class="app-muted-text">Contact</dt><dd class="app-heading-text mt-1">{{ reviewing.contactName }} &lt;{{ reviewing.contactEmail }}&gt;</dd></div>
        <div class="grid grid-cols-2 gap-4">
          <div><dt class="app-muted-text">Annual revenue</dt><dd class="app-heading-text mt-1 tabular-nums">{{ formatMoney(reviewing.annualRevenue, 'usd') }}</dd></div>
          <div><dt class="app-muted-text">Capital raised</dt><dd class="app-heading-text mt-1 tabular-nums">{{ formatMoney(reviewing.capitalRaised, 'usd') }}</dd></div>
        </div>
        <div><dt class="app-muted-text">Intended use</dt><dd class="app-heading-text mt-1 whitespace-pre-wrap">{{ reviewing.intendedUse }}</dd></div>
        <div><dt class="app-muted-text">Requested</dt><dd class="app-heading-text mt-1">{{ formatDate(reviewing.createdAt) }}</dd></div>
      </dl>
      <template #footer>
        <Button type="button" label="Reject" severity="danger" outlined :loading="deciding" @click="decide(false)" />
        <Button type="button" label="Approve and issue key" :loading="deciding" @click="decide(true)" />
      </template>
    </FormDrawer>
  </div>
</template>
