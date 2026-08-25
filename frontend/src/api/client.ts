import type {
  GeneratedBatch,
  LatestVersion,
  LicensePage,
  PluginSummary,
  PluginVersion,
  PublicPlugin,
  UserAuthResult,
  UserLicense,
  UserPlugin,
  UserProfile,
} from './types';

export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
  ) {
    super(message);
  }
}

async function request<T>(path: string, init: RequestInit = {}, adminKey?: string): Promise<T> {
  const headers = new Headers(init.headers);
  if (!(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }
  if (adminKey) {
    headers.set('Authorization', `Bearer ${adminKey}`);
  }
  const response = await fetch(path, { ...init, headers });
  if (!response.ok) {
    const body = (await response.json().catch(() => null)) as
      | { code?: string; message?: string }
      | null;
    throw new ApiError(body?.code ?? 'REQUEST_FAILED', body?.message ?? '请求失败', response.status);
  }
  return response.json() as Promise<T>;
}

export const adminApi = {
  getPlugins: (key: string): Promise<PluginSummary[]> =>
    request('/api/v1/admin/plugins', {}, key),

  createPlugin: (
    key: string,
    body: { slug: string; name: string; description: string; verificationMessage: string },
  ): Promise<PluginSummary['plugin']> =>
    request('/api/v1/admin/plugins', { method: 'POST', body: JSON.stringify(body) }, key),

  getLicenses: (key: string, pluginId: string, page = 1): Promise<LicensePage> =>
    request(`/api/v1/admin/plugins/${pluginId}/licenses?page=${page}&pageSize=20`, {}, key),

  generateLicenses: (
    key: string,
    pluginId: string,
    body: { count: number; durationDays: number | null; customMessage: string },
  ): Promise<GeneratedBatch> =>
    request(
      `/api/v1/admin/plugins/${pluginId}/licenses`,
      { method: 'POST', body: JSON.stringify(body) },
      key,
    ),

  updateLicenseStatus: (
    key: string,
    licenseId: string,
    status: 'ACTIVE' | 'REVOKED',
  ): Promise<{ success: boolean }> =>
    request(
      `/api/v1/admin/licenses/${licenseId}/status`,
      { method: 'PATCH', body: JSON.stringify({ status }) },
      key,
    ),

  unbindLicense: (key: string, licenseId: string): Promise<{ success: boolean }> =>
    request(`/api/v1/admin/licenses/${licenseId}/unbind`, { method: 'POST' }, key),

  getVersions: (key: string, pluginId: string): Promise<PluginVersion[]> =>
    request(`/api/v1/admin/plugins/${pluginId}/versions`, {}, key),

  uploadVersion: (
    key: string,
    pluginId: string,
    version: string,
    changelog: string,
    isPublic: boolean,
    file: File,
  ): Promise<PluginVersion> => {
    const form = new FormData();
    form.set('file', file);
    const query = new URLSearchParams({ version, changelog, isPublic: String(isPublic) });
    return request(
      `/api/v1/admin/plugins/${pluginId}/versions?${query}`,
      { method: 'POST', body: form },
      key,
    );
  },
};

export const publicApi = {
  getPlugins: (): Promise<PublicPlugin[]> => request('/api/v1/public/plugins'),
  getLatest: (slug: string): Promise<LatestVersion> =>
    request(`/api/v1/public/plugins/${encodeURIComponent(slug)}/latest`),
};

export const userApi = {
  register: (body: { username: string; password: string }): Promise<UserAuthResult> =>
    request('/api/v1/users/register', { method: 'POST', body: JSON.stringify(body) }),

  login: (body: { username: string; password: string }): Promise<UserAuthResult> =>
    request('/api/v1/users/login', { method: 'POST', body: JSON.stringify(body) }),

  me: (token: string): Promise<UserProfile> => request('/api/v1/user/me', {}, token),

  logout: (token: string): Promise<{ success: boolean; message: string }> =>
    request('/api/v1/user/logout', { method: 'POST' }, token),

  getPlugins: (token: string): Promise<UserPlugin[]> =>
    request('/api/v1/user/plugins', {}, token),

  claimLicense: (
    token: string,
    body: { plugin: string; licenseKey: string },
  ): Promise<UserLicense> =>
    request('/api/v1/user/licenses/claim', { method: 'POST', body: JSON.stringify(body) }, token),

  unbindLicense: (token: string, licenseId: string): Promise<{ success: boolean; message: string }> =>
    request(`/api/v1/user/licenses/${licenseId}/unbind`, { method: 'POST' }, token),

  async downloadPlugin(token: string, slug: string): Promise<{ blob: Blob; fileName: string }> {
    const response = await fetch(`/api/v1/user/plugins/${encodeURIComponent(slug)}/download`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!response.ok) {
      const body = (await response.json().catch(() => null)) as
        | { code?: string; message?: string }
        | null;
      throw new ApiError(
        body?.code ?? 'DOWNLOAD_FAILED',
        body?.message ?? '插件下载失败',
        response.status,
      );
    }
    const disposition = response.headers.get('Content-Disposition') ?? '';
    const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
    return {
      blob: await response.blob(),
      fileName: encodedName ? decodeURIComponent(encodedName) : `${slug}.jar`,
    };
  },
};
