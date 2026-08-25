<!-- dev-expert · anti-slop: P5 H5 E5 S5 R5 V5 -->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import {
  AlertCircle, Box, CheckCircle2, Clock3, Download, KeyRound, LoaderCircle, LogIn, LogOut,
  PackageCheck, PackageOpen, RefreshCw, Server, ShieldCheck, Unlink, UserPlus,
} from '@lucide/vue';
import { ApiError, publicApi, userApi } from '../api/client';
import type { PublicPlugin, UserPlugin, UserProfile } from '../api/types';

type AuthMode = 'login' | 'register';
type NoticeKind = 'success' | 'error';

const authMode = ref<AuthMode>('login');
const username = ref('');
const password = ref('');
const token = ref('');
const profile = ref<UserProfile | null>(null);
const catalog = ref<PublicPlugin[]>([]);
const ownedPlugins = ref<UserPlugin[]>([]);
const selectedPlugin = ref('cloudfashion');
const licenseKey = ref('');
const authError = ref('');
const noticeMessage = ref('');
const noticeKind = ref<NoticeKind>('success');
const isAuthenticating = ref(false);
const isLoadingPlugins = ref(false);
const isClaiming = ref(false);
const pendingLicenseId = ref('');
const downloadingSlug = ref('');

const isAuthenticated = computed(() => token.value !== '' && profile.value !== null);
const activeLicenseCount = computed(() =>
  ownedPlugins.value.reduce(
    (count, plugin) => count + plugin.licenses.filter(
      (license) => license.status === 'ACTIVE' && !isExpired(license.expiresAt),
    ).length,
    0,
  ),
);

onMounted(loadCatalog);

async function loadCatalog(): Promise<void> {
  try {
    catalog.value = await publicApi.getPlugins();
    if (!catalog.value.some((plugin) => plugin.slug === selectedPlugin.value)) {
      selectedPlugin.value = catalog.value[0]?.slug ?? '';
    }
  } catch {
    catalog.value = [];
  }
}

async function handleAuthenticate(): Promise<void> {
  authError.value = '';
  isAuthenticating.value = true;
  try {
    const credentials = { username: username.value.trim(), password: password.value };
    const result = authMode.value === 'login'
      ? await userApi.login(credentials)
      : await userApi.register(credentials);
    token.value = result.token;
    profile.value = result.user;
    password.value = '';
    showNotice(authMode.value === 'login' ? '登录成功' : '账号已创建');
    await Promise.all([loadOwnedPlugins(), loadCatalog()]);
  } catch (error) {
    authError.value = errorMessage(error, '无法完成登录');
  } finally {
    isAuthenticating.value = false;
  }
}

async function handleLogout(): Promise<void> {
  const currentToken = token.value;
  clearSession();
  try {
    await userApi.logout(currentToken);
  } catch {
    // The local in-memory session is cleared even when the server is unreachable.
  }
}

async function loadOwnedPlugins(): Promise<void> {
  if (!token.value) return;
  isLoadingPlugins.value = true;
  try {
    ownedPlugins.value = await userApi.getPlugins(token.value);
  } catch (error) {
    handleApiError(error, '无法加载我的插件');
  } finally {
    isLoadingPlugins.value = false;
  }
}

async function handleClaim(): Promise<void> {
  if (!token.value || !selectedPlugin.value || !licenseKey.value.trim()) return;
  isClaiming.value = true;
  noticeMessage.value = '';
  try {
    await userApi.claimLicense(token.value, {
      plugin: selectedPlugin.value,
      licenseKey: licenseKey.value.trim(),
    });
    licenseKey.value = '';
    showNotice('卡密已领取，插件已加入你的账号');
    await loadOwnedPlugins();
  } catch (error) {
    handleApiError(error, '卡密领取失败');
  } finally {
    isClaiming.value = false;
  }
}

async function handleUnbind(plugin: UserPlugin, licenseId: string): Promise<void> {
  if (!token.value || !window.confirm(`确认解除 ${plugin.name} 的服务器 IP 绑定？`)) return;
  pendingLicenseId.value = licenseId;
  try {
    await userApi.unbindLicense(token.value, licenseId);
    showNotice('IP 绑定已解除，下次验证时会绑定新的服务器 IP');
    await loadOwnedPlugins();
  } catch (error) {
    handleApiError(error, '解绑失败');
  } finally {
    pendingLicenseId.value = '';
  }
}

async function handleDownload(plugin: UserPlugin): Promise<void> {
  if (!token.value || !plugin.downloadable || !plugin.latest) return;
  downloadingSlug.value = plugin.slug;
  try {
    const file = await userApi.downloadPlugin(token.value, plugin.slug);
    const objectUrl = URL.createObjectURL(file.blob);
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = file.fileName;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(objectUrl);
    showNotice(`${plugin.name} v${plugin.latest.version} 已开始下载`);
  } catch (error) {
    handleApiError(error, '插件下载失败');
  } finally {
    downloadingSlug.value = '';
  }
}

function clearSession(): void {
  token.value = '';
  profile.value = null;
  ownedPlugins.value = [];
  noticeMessage.value = '';
  licenseKey.value = '';
}

function selectAuthMode(mode: AuthMode): void {
  authMode.value = mode;
  authError.value = '';
}

function handleApiError(error: unknown, fallback: string): void {
  if (error instanceof ApiError && error.status === 401) {
    clearSession();
    authError.value = '登录已失效，请重新登录';
    return;
  }
  showNotice(errorMessage(error, fallback), 'error');
}

function showNotice(message: string, kind: NoticeKind = 'success'): void {
  noticeMessage.value = message;
  noticeKind.value = kind;
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

function formatDate(value: string | null): string {
  if (!value) return '永久有效';
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value));
}

function formatSize(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function isExpired(value: string | null): boolean {
  return value !== null && new Date(value).getTime() <= Date.now();
}
</script>

<template>
  <main v-if="!isAuthenticated" class="account-entry">
    <section class="entry-intro" aria-labelledby="entry-title">
      <div class="entry-brand"><Box :size="30" /><span>CloudLicense</span></div>
      <p class="entry-kicker">USER ACCESS / LICENSE CONTROL</p>
      <h1 id="entry-title">管理你拥有的插件</h1>
      <p>领取卡密、维护服务器 IP 绑定，并下载账号已授权插件的最新版本。</p>
      <div class="entry-points" aria-label="账户功能">
        <span><ShieldCheck :size="18" /> 授权归属</span>
        <span><Unlink :size="18" /> 自助解绑</span>
        <span><Download :size="18" /> 鉴权下载</span>
      </div>
    </section>

    <section class="auth-box" aria-label="用户账户">
      <div class="auth-switch" aria-label="账户操作">
        <button :class="{ active: authMode === 'login' }" type="button" @click="selectAuthMode('login')">
          <LogIn :size="17" /> 登录
        </button>
        <button :class="{ active: authMode === 'register' }" type="button" @click="selectAuthMode('register')">
          <UserPlus :size="17" /> 注册
        </button>
      </div>
      <div class="auth-heading">
        <KeyRound :size="24" />
        <div>
          <h2>{{ authMode === 'login' ? '进入用户中心' : '创建用户账号' }}</h2>
          <p>{{ authMode === 'login' ? '使用你的账号继续' : '注册后即可领取已有卡密' }}</p>
        </div>
      </div>
      <form class="auth-form" @submit.prevent="handleAuthenticate">
        <label>
          用户名
          <input v-model="username" autocomplete="username" minlength="3" maxlength="32"
            pattern="[A-Za-z0-9_]+" placeholder="3-32 位字母、数字或下划线" required />
        </label>
        <label>
          密码
          <input v-model="password" :autocomplete="authMode === 'login' ? 'current-password' : 'new-password'"
            type="password" minlength="8" maxlength="64" placeholder="至少 8 位" required />
        </label>
        <p v-if="authError" class="form-feedback form-feedback--error" role="alert">
          <AlertCircle :size="17" />{{ authError }}
        </p>
        <button class="primary-action" :disabled="isAuthenticating" type="submit">
          <LoaderCircle v-if="isAuthenticating" class="spin" :size="18" />
          <LogIn v-else-if="authMode === 'login'" :size="18" />
          <UserPlus v-else :size="18" />
          {{ isAuthenticating ? '正在处理' : authMode === 'login' ? '登录' : '注册并登录' }}
        </button>
      </form>
    </section>
  </main>

  <main v-else class="user-portal">
    <header class="portal-header">
      <div class="portal-brand">
        <span class="portal-mark"><Box :size="24" /></span>
        <div><strong>CloudLicense</strong><span>用户中心</span></div>
      </div>
      <div class="portal-account">
        <div><span>当前账号</span><strong>{{ profile?.username }}</strong></div>
        <button type="button" title="退出登录" @click="handleLogout"><LogOut :size="18" /><span>退出</span></button>
      </div>
    </header>

    <section class="claim-workbench" aria-labelledby="claim-title">
      <div class="claim-heading">
        <span><KeyRound :size="22" /></span>
        <div><p>CLAIM LICENSE</p><h1 id="claim-title">领取插件卡密</h1></div>
      </div>
      <form class="claim-form" @submit.prevent="handleClaim">
        <label>
          插件
          <select v-model="selectedPlugin" :disabled="catalog.length === 0" required>
            <option v-if="catalog.length === 0" value="">插件目录不可用</option>
            <option v-for="plugin in catalog" :key="plugin.slug" :value="plugin.slug">{{ plugin.name }}</option>
          </select>
        </label>
        <label>
          卡密
          <input v-model="licenseKey" autocomplete="off" maxlength="64"
            placeholder="CLD-XXXXX-XXXXX-XXXXX-XXXXX" required />
        </label>
        <button class="primary-action" :disabled="isClaiming || !selectedPlugin" type="submit">
          <LoaderCircle v-if="isClaiming" class="spin" :size="18" />
          <PackageCheck v-else :size="18" />
          {{ isClaiming ? '正在领取' : '领取到账号' }}
        </button>
      </form>
    </section>

    <section v-if="noticeMessage" class="portal-notice" :class="`portal-notice--${noticeKind}`"
      :role="noticeKind === 'error' ? 'alert' : 'status'">
      <AlertCircle v-if="noticeKind === 'error'" :size="18" />
      <CheckCircle2 v-else :size="18" />
      <span>{{ noticeMessage }}</span>
      <button type="button" aria-label="关闭提示" @click="noticeMessage = ''">×</button>
    </section>

    <section class="owned-section" aria-labelledby="owned-title">
      <div class="owned-toolbar">
        <div><p>MY REPOSITORY</p><h2 id="owned-title">我的插件</h2></div>
        <div class="owned-summary">
          <span>{{ ownedPlugins.length }} 个插件</span>
          <span>{{ activeLicenseCount }} 个有效授权</span>
          <button type="button" title="刷新我的插件" :disabled="isLoadingPlugins" @click="loadOwnedPlugins">
            <LoaderCircle v-if="isLoadingPlugins" class="spin" :size="18" />
            <RefreshCw v-else :size="18" />
          </button>
        </div>
      </div>

      <div v-if="isLoadingPlugins && ownedPlugins.length === 0" class="portal-state">
        <LoaderCircle class="spin" :size="26" /><p>正在加载你的插件</p>
      </div>
      <div v-else-if="ownedPlugins.length === 0" class="portal-state">
        <PackageOpen :size="28" /><p>还没有已领取的插件</p><span>在上方输入卡密后，插件会出现在这里。</span>
      </div>

      <div v-else class="plugin-repository">
        <article v-for="plugin in ownedPlugins" :key="plugin.slug" class="owned-plugin">
          <header class="owned-plugin__header">
            <div class="owned-plugin__identity">
              <span><Server :size="23" /></span>
              <div><h3>{{ plugin.name }}</h3><code>{{ plugin.slug }}</code></div>
            </div>
            <div class="release-info">
              <span>最新版本</span>
              <strong>{{ plugin.latest ? `v${plugin.latest.version}` : '未发布' }}</strong>
              <small v-if="plugin.latest">{{ formatSize(plugin.latest.sizeBytes) }}</small>
            </div>
            <button class="download-button"
              :disabled="!plugin.downloadable || !plugin.latest || downloadingSlug === plugin.slug"
              type="button" @click="handleDownload(plugin)">
              <LoaderCircle v-if="downloadingSlug === plugin.slug" class="spin" :size="18" />
              <Download v-else :size="18" />
              {{ downloadingSlug === plugin.slug ? '准备文件' : plugin.latest ? '下载最新版' : '暂无版本' }}
            </button>
          </header>

          <p v-if="plugin.latest?.changelog" class="release-note">{{ plugin.latest.changelog }}</p>

          <div class="license-list" aria-label="插件授权">
            <div v-for="license in plugin.licenses" :key="license.id" class="license-row">
              <div class="license-key"><span>卡密</span><code>{{ license.key }}</code></div>
              <div class="license-detail">
                <span>状态</span>
                <strong :class="license.status === 'ACTIVE' && !isExpired(license.expiresAt) ? 'state-good' : 'state-bad'">
                  {{ license.status === 'REVOKED' ? '已停用' : isExpired(license.expiresAt) ? '已过期' : '有效' }}
                </strong>
              </div>
              <div class="license-detail">
                <span>有效期</span><strong><Clock3 :size="14" />{{ formatDate(license.expiresAt) }}</strong>
              </div>
              <div class="license-detail">
                <span>绑定 IP</span><strong>{{ license.boundIp || '尚未绑定' }}</strong>
              </div>
              <button class="unbind-button" :disabled="!license.boundIp || pendingLicenseId === license.id"
                type="button" :title="license.boundIp ? '解除此授权的 IP 绑定' : '此授权尚未绑定 IP'"
                @click="handleUnbind(plugin, license.id)">
                <LoaderCircle v-if="pendingLicenseId === license.id" class="spin" :size="17" />
                <Unlink v-else :size="17" />
                <span>{{ pendingLicenseId === license.id ? '解绑中' : '解绑 IP' }}</span>
              </button>
            </div>
          </div>
        </article>
      </div>
    </section>
  </main>
</template>
