<script setup lang="ts">
import { computed } from 'vue'
import Tag from 'primevue/tag'
import { InitialsTile } from '@kinotic-ai/frontend-common'
import type { IssuedKey, LicensedCluster, LicenseWithKeys } from '@/api/model'
import KeyBlock from '@/components/KeyBlock.vue'
import { formatDate } from '@/util/format'

/**
 * A license's clusters, one row each: a cluster holding a seat with its id, when it was added and last renewed
 * online, its newest key and the keys before it, marked when it is beyond the license's seats and gets no newer key;
 * then the clusters that moved to another license after this one ended.
 */
const props = defineProps<{
  entry: LicenseWithKeys
}>()

const holding = computed(() => props.entry.clusters.filter(cluster => cluster.releasedAt === null))
const released = computed(() => props.entry.clusters.filter(cluster => cluster.releasedAt !== null))

// the keys are newest first, so a cluster's first is its newest
function keysOf(cluster: LicensedCluster): IssuedKey[] {
  return props.entry.keys.filter(key => key.licensedClusterId === cluster.id)
}
</script>

<template>
  <div>
    <div v-for="(cluster, index) in holding" :key="cluster.id" class="app-surface-divider flex flex-col gap-3 border-t px-5 py-4 first:border-t-0">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div class="flex min-w-0 items-center gap-3">
          <InitialsTile :name="cluster.name" :index="index" size="sm" />
          <div class="min-w-0 text-sm">
            <div class="flex flex-wrap items-center gap-2">
              <span class="app-heading-text font-medium">{{ cluster.name }}</span>
              <Tag v-if="index >= entry.license.seats" severity="warn" value="Beyond your seats" />
            </div>
            <div class="app-muted-text break-all font-mono text-xs">{{ cluster.clusterId }}</div>
          </div>
        </div>
        <div class="app-muted-text text-xs sm:text-right">
          <div>Added {{ formatDate(cluster.addedAt) }}</div>
          <div>{{ cluster.lastRenewedAt ? `Last renewed online ${formatDate(cluster.lastRenewedAt)}` : 'Never renewed online' }}</div>
        </div>
      </div>
      <p v-if="index >= entry.license.seats" class="text-sm text-orange-600 dark:text-orange-400">
        The license has {{ entry.license.seats }} {{ entry.license.seats === 1 ? 'seat' : 'seats' }}, so this cluster gets no newer key.
        Add seats to keep it licensed.
      </p>
      <KeyBlock v-if="keysOf(cluster).length" :issued-key="keysOf(cluster)[0]!" />
      <details v-if="keysOf(cluster).length > 1" class="text-sm">
        <summary class="app-muted-text cursor-pointer text-xs">Earlier keys ({{ keysOf(cluster).length - 1 }})</summary>
        <div class="mt-2 flex flex-col gap-2">
          <KeyBlock v-for="key in keysOf(cluster).slice(1)" :key="key.jti" :issued-key="key" />
        </div>
      </details>
    </div>
    <p v-if="!holding.length" class="app-muted-text px-5 py-6 text-center text-sm">
      No cluster holds a seat yet. Select License this cluster on its system console's License page, or add it here with the
      id that page shows.
    </p>
    <ul v-if="released.length" class="app-muted-text app-surface-divider space-y-1 border-t px-5 py-3 text-xs">
      <li v-for="cluster in released" :key="cluster.id">
        {{ cluster.name }} · <span class="font-mono">{{ cluster.clusterId }}</span> · moved to another license {{ formatDate(cluster.releasedAt) }}
      </li>
    </ul>
  </div>
</template>
