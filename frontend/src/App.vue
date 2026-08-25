<!-- dev-expert · anti-slop: P5 H5 E5 S5 R5 V4 -->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  Boxes,
  Check,
  ChevronLeft,
  ChevronRight,
  CircleAlert,
  CloudUpload,
  Copy,
  Download,
  FileArchive,
  KeyRound,
  LayoutDashboard,
  LoaderCircle,
  LockKeyhole,
  LogOut,
  PackagePlus,
  Plus,
  RefreshCw,
  ShieldCheck,
  Unplug,
  X,
} from '@lucide/vue';
import { adminApi, ApiError } from './api/client';
import type {
  GeneratedBatch,
  LicenseItem,
  LicensePage,
  PluginSummary,
  PluginVersion,
} from './api/types';

type View = 'overview' | 'licenses' | 'obfuscate';

const adminKey = ref('');
const keyInput = ref('');
const activeView = ref<View>('overview');
const plugins = ref<PluginSummary[]>([]);
const selectedPluginId = ref('');
const licenses = ref<LicensePage>({ items: [], page: 1, pageSize: 20, total: 0 });
const versions = ref<PluginVersion[]>([]);
const isLoading = ref(false);
const errorMessage = ref('');
const successMessage = ref('');

const isCreateOpen = ref(false);
const createForm = ref({ slug: '', name: '', description: '', verificationMessage: '' });

const isGenerateOpen = ref(false);
const generateForm = ref({ count: 1, duration: '30', customMessage: '' });
const generatedBatch = ref<GeneratedBatch | null>(null);

const uploadForm = ref({ version: '', changelog: '', isPublic: true });
const uploadFile = ref<File | null>(null);

const selectedPlugin = computed(() =>
  plugins.value.find((item) => item.plugin.id === selectedPluginId.value),
);
const activeLicenses = computed(() =>
  licenses.value.items.filter((item) => item.status === 'ACTIVE').length,
);
const totalLicenses = computed(() =>
  plugins.value.reduce((total, item) => total + item.licenseCount, 0),
);
const publishedPlugins = computed(() =>
  plugins.value.filter((item) => item.latestVersion !== null).length,
);

onMounted(() => {
  const firstInput = document.querySelector<HTMLInputElement>('#admin-key');
  firstInput?.focus();
});

async function handleLogin(): Promise<void> {
  errorMessage.value = '';
  isLoading.value = true;
  try {
    const data = await adminApi.getPlugins(keyInput.value.trim());
    adminKey.value = keyInput.value.trim();
    plugins.value = data;
    selectedPluginId.value = data[0]?.plugin.id ?? '';
  } catch (error) {
    errorMessage.value = getErrorMessage(error);
  } finally {
    isLoading.value = false;
  }
}

function handleLogout(): void {
  adminKey.value = '';
  keyInput.value = '';
  plugins.value = [];
  licenses.value = { items: [], page: 1, pageSize: 20, total: 0 };
  versions.value = [];
}

async function refreshPlugins(): Promise<void> {
  plugins.value = await adminApi.getPlugins(adminKey.value);
  if (!selectedPluginId.value && plugins.value.length > 0) {
    selectedPluginId.value = plugins.value[0].plugin.id;
  }
}

async function switchView(view: View): Promise<void> {
  activeView.value = view;
  errorMessage.value = '';
  if (view === 'licenses' && selectedPluginId.value) {
    await loadLicenses(1);
  }
  if (view === 'obfuscate' && selectedPluginId.value) {
    await loadVersions();
  }
}

async function selectPlugin(pluginId: string): Promise<void> {
  selectedPluginId.value = pluginId;
  if (activeView.value === 'licenses') {
    await loadLicenses(1);
  } else if (activeView.value === 'obfuscate') {
    await loadVersions();
  }
}

async function createPlugin(): Promise<void> {
  await runAction(async () => {
    await adminApi.createPlugin(adminKey.value, createForm.value);
    await refreshPlugins();
    isCreateOpen.value = false;
    createForm.value = { slug: '', name: '', description: '', verificationMessage: '' };
    successMessage.value = '授权列表已创建';
  });
}

async function loadLicenses(page: number): Promise<void> {
  if (!selectedPluginId.value) return;
  await runAction(async () => {
    licenses.value = await adminApi.getLicenses(adminKey.value, selectedPluginId.value, page);
  }, false);
}

async function generateLicenses(): Promise<void> {
  await runAction(async () => {
    const durationDays = generateForm.value.duration === 'permanent'
      ? null
      : Number(generateForm.value.duration);
    generatedBatch.value = await adminApi.generateLicenses(
      adminKey.value,
      selectedPluginId.value,
      {
        count: generateForm.value.count,
        durationDays,
        customMessage: generateForm.value.customMessage,
      },
    );
    await Promise.all([loadLicenses(1), refreshPlugins()]);
  });
}

async function toggleLicense(item: LicenseItem): Promise<void> {
  const nextStatus = item.status === 'ACTIVE' ? 'REVOKED' : 'ACTIVE';
  await runAction(async () => {
    await adminApi.updateLicenseStatus(adminKey.value, item.id, nextStatus);
    await loadLicenses(licenses.value.page);
    successMessage.value = nextStatus === 'ACTIVE' ? '授权已恢复' : '授权已停用';
  });
}

async function unbindLicense(item: LicenseItem): Promise<void> {
  await runAction(async () => {
    await adminApi.unbindLicense(adminKey.value, item.id);
    await loadLicenses(licenses.value.page);
    successMessage.value = 'IP 绑定已解除';
  });
}

async function loadVersions(): Promise<void> {
  if (!selectedPluginId.value) return;
  await runAction(async () => {
    versions.value = await adminApi.getVersions(adminKey.value, selectedPluginId.value);
  }, false);
}

function handleFile(event: Event): void {
  const input = event.target as HTMLInputElement;
  uploadFile.value = input.files?.[0] ?? null;
}

async function uploadVersion(): Promise<void> {
  if (!uploadFile.value) {
    errorMessage.value = '请选择 JAR 文件';
    return;
  }
  await runAction(async () => {
    await adminApi.uploadVersion(
      adminKey.value,
      selectedPluginId.value,
      uploadForm.value.version,
      uploadForm.value.changelog,
      uploadForm.value.isPublic,
      uploadFile.value as File,
    );
    uploadForm.value = { version: '', changelog: '', isPublic: true };
    uploadFile.value = null;
    await Promise.all([loadVersions(), refreshPlugins()]);
    successMessage.value = 'JNI 混淆完成，版本已发布到插件仓库';
  });
}

async function copyGeneratedKeys(): Promise<void> {
  if (!generatedBatch.value) return;
  await navigator.clipboard.writeText(generatedBatch.value.keys.join('\n'));
  successMessage.value = '卡密已复制';
}

function downloadGeneratedKeys(): void {
  if (!generatedBatch.value) return;
  const blob = new Blob([generatedBatch.value.keys.join('\n')], { type: 'text/plain;charset=utf-8' });
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = `${generatedBatch.value.plugin}-licenses.txt`;
  link.click();
  URL.revokeObjectURL(link.href);
}

async function runAction(action: () => Promise<void>, showLoader = true): Promise<void> {
  errorMessage.value = '';
  successMessage.value = '';
  if (showLoader) isLoading.value = true;
  try {
    await action();
  } catch (error) {
    errorMessage.value = getErrorMessage(error);
  } finally {
    if (showLoader) isLoading.value = false;
  }
}

function getErrorMessage(error: unknown): string {
  if (error instanceof ApiError || error instanceof Error) return error.message;
  return '请求失败，请稍后重试';
}

function formatDate(value: string | null): string {
  if (!value) return '永久';
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

function formatBytes(value: number): string {
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}
</script>

<template>
  <main v-if="!adminKey" class="auth-shell">
    <section class="auth-panel" aria-labelledby="auth-title">
      <div class="brand-mark" aria-hidden="true"><ShieldCheck :size="28" /></div>
      <p class="eyebrow">CLOUDLICENSE / ADMIN</p>
      <h1 id="auth-title">验证管理身份</h1>
      <p class="auth-copy">输入服务端配置的管理员密钥，密钥只保留在当前页面内存中。</p>
      <form class="auth-form" @submit.prevent="handleLogin">
        <label for="admin-key">管理员密钥</label>
        <div class="input-with-icon">
          <LockKeyhole :size="18" aria-hidden="true" />
          <input
            id="admin-key"
            v-model="keyInput"
            type="password"
            autocomplete="off"
            placeholder="Bearer key"
            required
          />
        </div>
        <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
        <button class="button button--primary button--wide" type="submit" :disabled="isLoading">
          <LoaderCircle v-if="isLoading" class="spin" :size="18" />
          <ShieldCheck v-else :size="18" />
          {{ isLoading ? '正在验证' : '进入控制台' }}
        </button>
      </form>
    </section>
  </main>

  <div v-else class="admin-shell">
    <aside class="sidebar">
      <div class="sidebar__brand">
        <div class="brand-mark brand-mark--small"><Boxes :size="22" /></div>
        <div><strong>CloudLicense</strong><span>授权控制台</span></div>
      </div>
      <nav class="sidebar__nav" aria-label="管理功能">
        <button
          :class="{ active: activeView === 'overview' }"
          aria-label="总览"
          title="总览"
          @click="switchView('overview')"
        >
          <LayoutDashboard :size="19" /><span>总览</span>
        </button>
        <button
          :class="{ active: activeView === 'licenses' }"
          aria-label="授权管理"
          title="授权管理"
          @click="switchView('licenses')"
        >
          <KeyRound :size="19" /><span>授权管理</span>
        </button>
        <button
          :class="{ active: activeView === 'obfuscate' }"
          aria-label="混淆与版本"
          title="混淆与版本"
          @click="switchView('obfuscate')"
        >
          <CloudUpload :size="19" /><span>混淆与版本</span>
        </button>
      </nav>
      <button class="sidebar__logout" title="退出管理控制台" @click="handleLogout">
        <LogOut :size="18" /><span>退出</span>
      </button>
    </aside>

    <main class="workspace">
      <header class="workspace__header">
        <div>
          <p class="eyebrow">CLOUDLICENSE CONTROL PLANE</p>
          <h1>
            {{ activeView === 'overview' ? '系统总览' : activeView === 'licenses' ? '授权管理' : '混淆与版本' }}
          </h1>
        </div>
        <label v-if="activeView !== 'overview'" class="compact-field">
          <span>当前插件</span>
          <select :value="selectedPluginId" @change="selectPlugin(($event.target as HTMLSelectElement).value)">
            <option v-for="item in plugins" :key="item.plugin.id" :value="item.plugin.id">
              {{ item.plugin.name }}
            </option>
          </select>
        </label>
      </header>

      <div v-if="errorMessage" class="notice notice--error" role="alert">
        <CircleAlert :size="18" />{{ errorMessage }}
        <button title="关闭错误提示" @click="errorMessage = ''"><X :size="17" /></button>
      </div>
      <div v-if="successMessage" class="notice notice--success" role="status">
        <Check :size="18" />{{ successMessage }}
        <button title="关闭成功提示" @click="successMessage = ''"><X :size="17" /></button>
      </div>

      <template v-if="activeView === 'overview'">
        <section class="stat-strip" aria-label="系统统计">
          <div><span>授权列表</span><strong>{{ plugins.length }}</strong></div>
          <div><span>累计卡密</span><strong>{{ totalLicenses }}</strong></div>
          <div><span>已发布插件</span><strong>{{ publishedPlugins }}</strong></div>
        </section>

        <section class="content-section">
          <div class="section-heading">
            <div><h2>插件授权列表</h2><p>每个插件拥有独立的卡密、IP 绑定和验证消息。</p></div>
            <button class="button button--primary" @click="isCreateOpen = true">
              <Plus :size="18" />创建列表
            </button>
          </div>
          <div class="table-wrap">
            <table>
              <thead><tr><th>插件</th><th>标识</th><th>卡密</th><th>最新版本</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="item in plugins" :key="item.plugin.id">
                  <td><strong>{{ item.plugin.name }}</strong><small>{{ item.plugin.description }}</small></td>
                  <td><code>{{ item.plugin.slug }}</code></td>
                  <td>{{ item.licenseCount }}</td>
                  <td><span class="badge">{{ item.latestVersion ?? '未发布' }}</span></td>
                  <td>
                    <button class="icon-button" title="查看授权" @click="selectedPluginId = item.plugin.id; switchView('licenses')">
                      <KeyRound :size="18" />
                    </button>
                  </td>
                </tr>
                <tr v-if="plugins.length === 0"><td colspan="5" class="empty-cell">暂无授权列表</td></tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>

      <template v-if="activeView === 'licenses'">
        <section class="content-section">
          <div class="section-heading">
            <div>
              <h2>{{ selectedPlugin?.plugin.name ?? '选择插件' }} 授权</h2>
              <p>{{ licenses.total }} 条记录，当前页 {{ activeLicenses }} 条有效。</p>
            </div>
            <button class="button button--primary" :disabled="!selectedPluginId" @click="isGenerateOpen = true; generatedBatch = null">
              <KeyRound :size="18" />生成卡密
            </button>
          </div>
          <div class="table-wrap">
            <table>
              <thead><tr><th>卡密</th><th>状态</th><th>绑定 IP</th><th>有效期</th><th>最后验证</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="item in licenses.items" :key="item.id">
                  <td><code>{{ item.key }}</code></td>
                  <td><span :class="['status', item.status === 'ACTIVE' ? 'status--active' : 'status--muted']">{{ item.status === 'ACTIVE' ? '有效' : '停用' }}</span></td>
                  <td>{{ item.boundIp ?? '未绑定' }}</td>
                  <td>{{ formatDate(item.expiresAt) }}</td>
                  <td>{{ item.lastVerifiedAt ? formatDate(item.lastVerifiedAt) : '从未' }}</td>
                  <td class="table-actions">
                    <button class="icon-button" :title="item.status === 'ACTIVE' ? '停用授权' : '恢复授权'" @click="toggleLicense(item)">
                      <ShieldCheck :size="17" />
                    </button>
                    <button class="icon-button" title="解除 IP 绑定" :disabled="!item.boundIp" @click="unbindLicense(item)">
                      <Unplug :size="17" />
                    </button>
                  </td>
                </tr>
                <tr v-if="licenses.items.length === 0"><td colspan="6" class="empty-cell">尚未生成卡密</td></tr>
              </tbody>
            </table>
          </div>
          <div class="pagination">
            <button class="icon-button" title="上一页" :disabled="licenses.page <= 1" @click="loadLicenses(licenses.page - 1)"><ChevronLeft :size="18" /></button>
            <span>第 {{ licenses.page }} 页</span>
            <button class="icon-button" title="下一页" :disabled="licenses.page * licenses.pageSize >= licenses.total" @click="loadLicenses(licenses.page + 1)"><ChevronRight :size="18" /></button>
          </div>
        </section>
      </template>

      <template v-if="activeView === 'obfuscate'">
        <section class="obfuscation-layout">
          <form class="upload-tool" @submit.prevent="uploadVersion">
            <div class="section-heading">
              <div><h2>JNI 一键混淆</h2><p>上传 Minecraft 插件 JAR，处理完成后自动进入版本仓库。</p></div>
              <FileArchive :size="28" aria-hidden="true" />
            </div>
            <label>版本号<input v-model="uploadForm.version" required placeholder="1.0.0" pattern="[0-9A-Za-z][0-9A-Za-z._-]{0,63}" /></label>
            <label>更新说明<textarea v-model="uploadForm.changelog" rows="4" maxlength="2000" placeholder="本版本的主要变化" /></label>
            <label class="drop-zone">
              <CloudUpload :size="30" />
              <span>{{ uploadFile?.name ?? '选择 JAR 文件' }}</span>
              <small>最大 100 MB，需包含 plugin.yml 或 paper-plugin.yml</small>
              <input type="file" accept=".jar,application/java-archive" required @change="handleFile" />
            </label>
            <label class="toggle-row"><input v-model="uploadForm.isPublic" type="checkbox" /><span>在下载中心公开此版本</span></label>
            <button class="button button--primary button--wide" type="submit" :disabled="isLoading || !selectedPluginId">
              <LoaderCircle v-if="isLoading" class="spin" :size="18" />
              <PackagePlus v-else :size="18" />
              {{ isLoading ? '正在处理' : '混淆并发布' }}
            </button>
          </form>

          <section class="repository-list">
            <div class="section-heading">
              <div><h2>版本仓库</h2><p>{{ selectedPlugin?.plugin.name }} 的全部构建。</p></div>
              <button class="icon-button" title="刷新版本" @click="loadVersions"><RefreshCw :size="18" /></button>
            </div>
            <article v-for="item in versions" :key="item.id" class="version-row">
              <div class="version-icon"><FileArchive :size="22" /></div>
              <div><strong>v{{ item.version }}</strong><p>{{ item.changelog || '无更新说明' }}</p><small>{{ formatDate(item.createdAt) }} · {{ formatBytes(item.sizeBytes) }}</small></div>
              <span :class="['status', item.isPublic ? 'status--active' : 'status--muted']">{{ item.isPublic ? '公开' : '私有' }}</span>
            </article>
            <div v-if="versions.length === 0" class="empty-state"><FileArchive :size="28" /><p>暂无已发布版本</p></div>
          </section>
        </section>
      </template>
    </main>

    <div v-if="isCreateOpen" class="modal-backdrop" @click.self="isCreateOpen = false">
      <section class="modal" role="dialog" aria-modal="true" aria-labelledby="create-title">
        <header><h2 id="create-title">创建授权列表</h2><button class="icon-button" title="关闭" @click="isCreateOpen = false"><X :size="19" /></button></header>
        <form @submit.prevent="createPlugin">
          <label>插件名称<input v-model="createForm.name" required maxlength="100" placeholder="CloudMarket" /></label>
          <label>插件标识<input v-model="createForm.slug" required pattern="[a-z0-9][a-z0-9-]{1,62}[a-z0-9]" placeholder="cloudmarket" /></label>
          <label>插件描述<textarea v-model="createForm.description" rows="3" maxlength="500" /></label>
          <label>默认验证消息<input v-model="createForm.verificationMessage" maxlength="300" placeholder="授权验证通过" /></label>
          <div class="modal__actions"><button type="button" class="button button--ghost" @click="isCreateOpen = false">取消</button><button class="button button--primary" :disabled="isLoading"><Plus :size="18" />创建</button></div>
        </form>
      </section>
    </div>

    <div v-if="isGenerateOpen" class="modal-backdrop" @click.self="isGenerateOpen = false">
      <section class="modal modal--wide" role="dialog" aria-modal="true" aria-labelledby="generate-title">
        <header><h2 id="generate-title">生成 {{ selectedPlugin?.plugin.name }} 卡密</h2><button class="icon-button" title="关闭" @click="isGenerateOpen = false"><X :size="19" /></button></header>
        <form v-if="!generatedBatch" @submit.prevent="generateLicenses">
          <div class="form-grid">
            <label>生成数量<input v-model.number="generateForm.count" type="number" min="1" max="100" required /></label>
            <label>有效时间<select v-model="generateForm.duration"><option value="7">7 天</option><option value="30">30 天</option><option value="90">90 天</option><option value="365">365 天</option><option value="permanent">永久</option></select></label>
          </div>
          <label>自定义验证消息<textarea v-model="generateForm.customMessage" rows="3" maxlength="300" placeholder="留空时使用插件默认消息" /></label>
          <div class="modal__actions"><button type="button" class="button button--ghost" @click="isGenerateOpen = false">取消</button><button class="button button--primary" :disabled="isLoading"><KeyRound :size="18" />生成卡密</button></div>
        </form>
        <div v-else class="generated-result">
          <div class="result-heading"><Check :size="22" /><div><strong>卡密已生成</strong><p>明文只在本次响应中返回，请立即妥善保存。</p></div></div>
          <pre>{{ generatedBatch.keys.join('\n') }}</pre>
          <div class="modal__actions"><button class="button button--ghost" @click="copyGeneratedKeys"><Copy :size="18" />复制</button><button class="button button--primary" @click="downloadGeneratedKeys"><Download :size="18" />下载 TXT</button></div>
        </div>
      </section>
    </div>
  </div>
</template>
