export interface Plugin {
  id: string;
  slug: string;
  name: string;
  description: string;
  verificationMessage: string;
  createdAt: string;
}

export interface PluginSummary {
  plugin: Plugin;
  licenseCount: number;
  latestVersion: string | null;
}

export interface LicenseItem {
  id: string;
  key: string;
  status: 'ACTIVE' | 'REVOKED';
  expiresAt: string | null;
  boundIp: string | null;
  customMessage: string | null;
  createdAt: string;
  lastVerifiedAt: string | null;
}

export interface LicensePage {
  items: LicenseItem[];
  page: number;
  pageSize: number;
  total: number;
}

export interface GeneratedBatch {
  plugin: string;
  keys: string[];
  expiresAt: string | null;
}

export interface PluginVersion {
  id: string;
  version: string;
  fileName: string;
  sha256: string;
  sizeBytes: number;
  changelog: string;
  isPublic: boolean;
  createdAt: string;
}

export interface PublicPlugin {
  slug: string;
  name: string;
  description: string;
  latestVersion: string | null;
  downloadUrl: string | null;
}

export interface LatestVersion {
  plugin: string;
  version: string;
  sha256: string;
  sizeBytes: number;
  changelog: string;
  publishedAt: string;
  downloadUrl: string;
}

export interface UserProfile {
  id: string;
  username: string;
  createdAt: string;
}

export interface UserAuthResult {
  token: string;
  expiresAt: string;
  user: UserProfile;
}

export interface UserLicense {
  id: string;
  key: string;
  status: 'ACTIVE' | 'REVOKED';
  expiresAt: string | null;
  boundIp: string | null;
  customMessage: string | null;
  createdAt: string;
  lastVerifiedAt: string | null;
}

export interface UserLatestRelease {
  version: string;
  sha256: string;
  sizeBytes: number;
  changelog: string;
  publishedAt: string;
}

export interface UserPlugin {
  slug: string;
  name: string;
  description: string;
  latest: UserLatestRelease | null;
  downloadable: boolean;
  licenses: UserLicense[];
}
