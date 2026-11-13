import { coreApiClient } from "@halo-dev/api-client";
import type { ConfigMap, Group, Policy } from "@halo-dev/api-client";
import type { RawAxiosRequestConfig } from "axios";
import { isNotFound } from "./nav";
import {
  isMediaKind,
  mediaKindFromUrl,
} from "@/utils/media";
import type { MediaKind } from "@/utils/media";

/**
 * Settings live in the plugin-owned `palette-nav-config` ConfigMap and are read /
 * written through Halo's core ConfigMap API. The ConfigMap is deliberately *not*
 * declared as the plugin's `spec.configMapName`: Halo's "uninstall and delete
 * config" deletes that ConfigMap, but these are business settings that must
 * survive an uninstall / reinstall. The 设置 tab in `PaletteNavView` is still the
 * only settings page; the schema and defaults are kept in sync with
 * `config/NavSettings.java` here.
 */
const SETTINGS_CONFIG_MAP = "palette-nav-config";

/** ConfigMap data values are JSON strings, one per NavSettings group. */
function parseConfigData(data?: Record<string, string>): Record<string, unknown> {
  const raw: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(data ?? {})) {
    if (typeof value !== "string" || !value.trim()) {
      continue;
    }
    try {
      raw[key] = JSON.parse(value);
    } catch {
      // A malformed group is ignored; normalizeSettings() falls back to defaults.
    }
  }
  return raw;
}

/** The settings tab renders its own error; avoid a second host toast. */
const SILENT_REQUEST = { mute: true } as RawAxiosRequestConfig;

export const THEME_COLORS = [
  { label: "极光紫", value: "purple" },
  { label: "板岩蓝", value: "blue" },
  { label: "森林绿", value: "green" },
  { label: "玫瑰粉", value: "pink" },
  { label: "日落橙", value: "orange" },
  { label: "海洋青", value: "teal" },
] as const;

export const NIGHT_MODES = [
  { label: "跟随系统", value: "auto" },
  { label: "亮色", value: "light" },
  { label: "夜间", value: "dark" },
] as const;

export interface GeneralSettings {
  siteName: string;
  siteDescription: string;
  siteKeywords: string;
  logoUrl: string;
  brandText: string;
  copyright: string;
  icp: string;
  gonganbei: string;
  /** Optional icon before the ICP record number; empty renders text only. */
  icpLogoUrl: string;
  /** Optional icon before the public security record number. */
  gonganbeiLogoUrl: string;
  /** Whether the public /nav page records visit statistics for the dashboard. */
  enableVisitStats: boolean;
  /**
   * Whether the public page footer shows today's PV/UV and the accumulated PV above
   * the record numbers. Independent of `enableVisitStats`.
   */
  showFooterVisitStats: boolean;
  /** Comma-separated proxy addresses allowed to set X-Forwarded-For / X-Real-IP. */
  trustedProxyIps: string;
}

export interface AppearanceSettings {
  defaultColor: string;
  defaultNightMode: string;
  showClock: boolean;
  showYiyan: boolean;
  /** Whether first-time visitors see the configured background immediately. */
  showBackgroundByDefault: boolean;
  /** Whether the in-page category nav shows each category's icon. */
  showCategoryIconsInNav: boolean;
  /** Merged background media: image or video, chosen by `backgroundType`. */
  backgroundUrl: string;
  backgroundType: MediaKind;
}

export interface SearchSettings {
  enableBaiduSuggestions: boolean;
}

/**
 * Where icons fetched from link targets are stored.
 *
 * Empty `policyName` means "highest priority storage policy" (the same policy
 * Halo's own upload UI selects), empty `groupName` means no group.
 */
export interface IconsSettings {
  policyName: string;
  groupName: string;
}

export interface NavSettingsValues {
  general: GeneralSettings;
  appearance: AppearanceSettings;
  search: SearchSettings;
  icons: IconsSettings;
}

const DEFAULT_VALUES: NavSettingsValues = {
  general: {
    siteName: "上网导航",
    siteDescription:
      "为 Halo 打造的上网导航页：分类、搜索、快捷入口、配色与背景随心换，不依赖主题，独立运行。",
    siteKeywords:
      "Palette Nav导航,百度搜索,哔哩哔哩搜索,知乎搜索,Palette Nav,网站导航,上网导航",
    logoUrl: "",
    brandText: "Palette Nav",
    copyright: "",
    icp: "",
    gonganbei: "",
    icpLogoUrl: "",
    gonganbeiLogoUrl: "",
    enableVisitStats: true,
    showFooterVisitStats: false,
    trustedProxyIps: "",
  },
  appearance: {
    defaultColor: "purple",
    defaultNightMode: "auto",
    showClock: true,
    showYiyan: true,
    showBackgroundByDefault: false,
    showCategoryIconsInNav: false,
    backgroundUrl: "",
    backgroundType: "image",
  },
  search: {
    enableBaiduSuggestions: true,
  },
  icons: {
    policyName: "",
    groupName: "",
  },
};

const COLOR_VALUES = new Set<string>(THEME_COLORS.map((item) => item.value));
const NIGHT_MODE_VALUES = new Set<string>(NIGHT_MODES.map((item) => item.value));

function asRecord(value: unknown): Record<string, unknown> {
  return typeof value === "object" && value !== null
    ? (value as Record<string, unknown>)
    : {};
}

function readString(value: unknown, fallback: string): string {
  return typeof value === "string" ? value : fallback;
}

function readBoolean(value: unknown, fallback: boolean): boolean {
  return typeof value === "boolean" ? value : fallback;
}

/** Mirrors `NavSettings.normalizeList`: trimmed, de-duplicated, no blanks. */
function normalizeIpList(value: string): string {
  const seen = new Set<string>();
  return value
    .split(",")
    .map((item) => item.trim())
    .filter((item) => {
      if (!item || seen.has(item)) {
        return false;
      }
      seen.add(item);
      return true;
    })
    .join(",");
}

/** Mirrors `NavSettings.normalized()` so a half-written config never leaks into the form. */
export function normalizeSettings(input: unknown): NavSettingsValues {
  const raw = asRecord(input);
  const general = asRecord(raw.general);
  const appearance = asRecord(raw.appearance);
  const search = asRecord(raw.search);
  const icons = asRecord(raw.icons);

  const color = readString(appearance.defaultColor, DEFAULT_VALUES.appearance.defaultColor)
    .trim()
    .toLowerCase();
  const nightMode = readString(
    appearance.defaultNightMode,
    DEFAULT_VALUES.appearance.defaultNightMode
  )
    .trim()
    .toLowerCase();

  // The two legacy background keys are merged into backgroundUrl/backgroundType;
  // read them as a fallback so existing installations keep their media until the
  // settings are saved once with the new keys.
  const legacyVideo = readString(appearance.backgroundVideo, "").trim();
  const legacyImage = readString(appearance.backgroundImage, "").trim();
  let backgroundUrl = readString(appearance.backgroundUrl, "").trim();
  let backgroundType = readString(appearance.backgroundType, "")
    .trim()
    .toLowerCase();
  if (!backgroundUrl) {
    if (legacyVideo) {
      backgroundUrl = legacyVideo;
      if (!isMediaKind(backgroundType)) {
        backgroundType = "video";
      }
    } else if (legacyImage) {
      backgroundUrl = legacyImage;
      if (!isMediaKind(backgroundType)) {
        backgroundType = "image";
      }
    }
  }
  // Mirrors `NavSettings.Appearance.normalized()`: an empty URL always renders the
  // default image background, whatever kind was stored, and a valid explicit kind
  // needs no extension to be recognised.
  const resolvedBackgroundType: MediaKind = backgroundUrl
    ? isMediaKind(backgroundType)
      ? backgroundType
      : mediaKindFromUrl(backgroundUrl)
    : "image";

  return {
    general: {
      siteName: readString(general.siteName, DEFAULT_VALUES.general.siteName).trim(),
      siteDescription: readString(
        general.siteDescription,
        DEFAULT_VALUES.general.siteDescription
      ).trim(),
      siteKeywords: readString(
        general.siteKeywords,
        DEFAULT_VALUES.general.siteKeywords
      ).trim(),
      logoUrl: readString(general.logoUrl, DEFAULT_VALUES.general.logoUrl).trim(),
      brandText: readString(general.brandText, DEFAULT_VALUES.general.brandText).trim(),
      copyright: readString(general.copyright, DEFAULT_VALUES.general.copyright).trim(),
      icp: readString(general.icp, DEFAULT_VALUES.general.icp).trim(),
      gonganbei: readString(general.gonganbei, DEFAULT_VALUES.general.gonganbei).trim(),
      icpLogoUrl: readString(
        general.icpLogoUrl,
        DEFAULT_VALUES.general.icpLogoUrl
      ).trim(),
      gonganbeiLogoUrl: readString(
        general.gonganbeiLogoUrl,
        DEFAULT_VALUES.general.gonganbeiLogoUrl
      ).trim(),
      enableVisitStats: readBoolean(
        general.enableVisitStats,
        DEFAULT_VALUES.general.enableVisitStats
      ),
      showFooterVisitStats: readBoolean(
        general.showFooterVisitStats,
        DEFAULT_VALUES.general.showFooterVisitStats
      ),
      trustedProxyIps: normalizeIpList(
        readString(
          general.trustedProxyIps,
          DEFAULT_VALUES.general.trustedProxyIps
        )
      ),
    },
    appearance: {
      defaultColor: COLOR_VALUES.has(color)
        ? color
        : DEFAULT_VALUES.appearance.defaultColor,
      defaultNightMode: NIGHT_MODE_VALUES.has(nightMode)
        ? nightMode
        : DEFAULT_VALUES.appearance.defaultNightMode,
      showClock: readBoolean(
        appearance.showClock,
        DEFAULT_VALUES.appearance.showClock
      ),
      showYiyan: readBoolean(
        appearance.showYiyan,
        DEFAULT_VALUES.appearance.showYiyan
      ),
      showBackgroundByDefault: readBoolean(
        appearance.showBackgroundByDefault,
        DEFAULT_VALUES.appearance.showBackgroundByDefault
      ),
      showCategoryIconsInNav: readBoolean(
        appearance.showCategoryIconsInNav,
        DEFAULT_VALUES.appearance.showCategoryIconsInNav
      ),
      backgroundUrl,
      backgroundType: resolvedBackgroundType,
    },
    search: {
      enableBaiduSuggestions: readBoolean(
        search.enableBaiduSuggestions,
        DEFAULT_VALUES.search.enableBaiduSuggestions
      ),
    },
    icons: {
      policyName: readString(
        icons.policyName,
        DEFAULT_VALUES.icons.policyName
      ).trim(),
      groupName: readString(
        icons.groupName,
        DEFAULT_VALUES.icons.groupName
      ).trim(),
    },
  };
}

export async function fetchNavSettings(): Promise<NavSettingsValues> {
  const { data } = await coreApiClient.configMap.getConfigMap(
    { name: SETTINGS_CONFIG_MAP },
    SILENT_REQUEST
  );
  return normalizeSettings(parseConfigData(data.data));
}

export async function saveNavSettings(values: NavSettingsValues): Promise<void> {
  const groups: Record<string, string> = {
    general: JSON.stringify(values.general),
    appearance: JSON.stringify(values.appearance),
    search: JSON.stringify(values.search),
    icons: JSON.stringify(values.icons),
  };

  // Re-read at save time so the update carries the current metadata.version and
  // keeps keys written by another console session; only the four known groups
  // are replaced.
  let current: ConfigMap | undefined;
  try {
    current = (
      await coreApiClient.configMap.getConfigMap(
        { name: SETTINGS_CONFIG_MAP },
        SILENT_REQUEST
      )
    ).data;
  } catch (error) {
    // A missing ConfigMap is a normal "never saved" state (`load()` treats it the
    // same way). The plugin recreates it on its next start; the manage role has no
    // permission to create ConfigMaps, so save must not try to create it here.
    if (!isNotFound(error)) {
      throw error;
    }
  }

  if (!current) {
    throw new Error("设置配置不存在，请重启插件后重试");
  }

  await coreApiClient.configMap.updateConfigMap(
    {
      name: SETTINGS_CONFIG_MAP,
      configMap: { ...current, data: { ...(current.data ?? {}), ...groups } },
    },
    SILENT_REQUEST
  );
}

/* ---------------------- 图标转存目标（存储策略 / 分组） ---------------------- */

export interface StorageOption {
  label: string;
  value: string;
}

/** Same labels the Halo console reads when it sorts / hides upload policies. */
const POLICY_PRIORITY_LABEL = "storage.halo.run/policy-priority-in-upload-ui";
const POLICY_HIDDEN_LABEL = "storage.halo.run/policy-hidden-in-upload-ui";
const GROUP_HIDDEN_LABEL = "halo.run/hidden";

/** Cycle guard for Halo's pagination; a normal list stops long before this. */
const MAX_LIST_PAGES = 1000;

async function listAllPolicies(): Promise<Policy[]> {
  const items: Policy[] = [];
  for (let page = 1; page <= MAX_LIST_PAGES; page += 1) {
    const { data } = await coreApiClient.storage.policy.listPolicy({
      page,
      size: 100,
    });
    items.push(...(data.items ?? []));
    if (!data.hasNext) {
      return items;
    }
  }
  throw new Error("存储策略分页超过安全上限");
}

async function listAllGroups(): Promise<Group[]> {
  const items: Group[] = [];
  for (let page = 1; page <= MAX_LIST_PAGES; page += 1) {
    const { data } = await coreApiClient.storage.group.listGroup({
      page,
      size: 100,
    });
    items.push(...(data.items ?? []));
    if (!data.hasNext) {
      return items;
    }
  }
  throw new Error("附件分组分页超过安全上限");
}

/**
 * Mirrors `NavIconStorageService#priority`: Java's `Integer.parseInt` accepts only a
 * signed int32, so inputs it would reject must not sort as a number here either.
 */
function policyPriority(policy: Policy): number {
  const raw = policy.metadata.labels?.[POLICY_PRIORITY_LABEL]?.trim();
  if (!raw || !/^[+-]?\d+$/.test(raw)) {
    return 0;
  }
  const parsed = Number.parseInt(raw, 10);
  return parsed >= -2_147_483_648 && parsed <= 2_147_483_647 ? parsed : 0;
}

/**
 * Matches `NavIconStorageService`'s `Instant.compareTo` in descending order. ISO
 * 8601 strings sort chronologically, but the comparison must be plain code-unit
 * ordering (`localeCompare` is locale-sensitive and can disagree).
 */
function compareCreationDesc(a?: string | null, b?: string | null): number {
  if (!a && !b) {
    return 0;
  }
  if (!a) {
    return 1;
  }
  if (!b) {
    return -1;
  }
  return b < a ? -1 : b > a ? 1 : 0;
}

/** Matches Java's `String.compareTo` tie-break (UTF-16 code units). */
function compareNames(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

/**
 * Storage policies offered by Halo's upload UI, highest priority first. The
 * plugin uses the first entry when no policy is explicitly configured, so the
 * tie-break (newest first, then name) must match NavIconStorageService.
 */
export async function listStoragePolicyOptions(): Promise<StorageOption[]> {
  return (await listAllPolicies())
    .filter((policy) => {
      const hidden = policy.metadata.labels?.[POLICY_HIDDEN_LABEL];
      // The backend hides with `equalsIgnoreCase("true")`.
      return (
        !policy.metadata.deletionTimestamp &&
        hidden?.toLowerCase() !== "true"
      );
    })
    .sort(
      (a, b) =>
        policyPriority(b) - policyPriority(a) ||
        compareCreationDesc(
          a.metadata.creationTimestamp,
          b.metadata.creationTimestamp
        ) ||
        compareNames(a.metadata.name, b.metadata.name)
    )
    .map((policy) => ({
      label: policy.spec?.displayName || policy.metadata.name,
      value: policy.metadata.name,
    }));
}

/**
 * Attachment groups that the console shows in its group lists.
 *
 * Halo's own group selector queries `labelSelector: ["!halo.run/hidden"]`, i.e.
 * the label merely being present hides the group, regardless of its value.
 */
export async function listAttachmentGroupOptions(): Promise<StorageOption[]> {
  return (await listAllGroups())
    .filter(
      (group) =>
        !group.metadata.deletionTimestamp &&
        group.metadata.labels?.[GROUP_HIDDEN_LABEL] === undefined
    )
    .sort((a, b) =>
      (a.spec?.displayName || a.metadata.name).localeCompare(
        b.spec?.displayName || b.metadata.name,
        "zh-CN"
      )
    )
    .map((group) => ({
      label: group.spec?.displayName || group.metadata.name,
      value: group.metadata.name,
    }));
}

/* ---------------------- 控制台「上传」的存储目标 ---------------------- */

export interface AttachmentUploadTarget {
  policyName: string;
  groupName?: string;
}

/**
 * Resolves where the console pickers' 「上传」 button stores files: the policy and
 * group configured in 设置 → 附件存储, so a manual upload lands in the same place
 * as icons fetched from link targets.
 *
 * Mirrors `NavIconStorageService`'s fallbacks: a deleted/renamed policy falls back
 * to the highest priority policy Halo's upload UI would pick, and a deleted group
 * falls back to "no group". Returns `null` when no policy can be resolved at all,
 * leaving the caller to use Halo's own console upload.
 */
export async function resolveAttachmentUploadTarget(): Promise<AttachmentUploadTarget | null> {
  let icons: IconsSettings = DEFAULT_VALUES.icons;
  try {
    icons = (await fetchNavSettings()).icons;
  } catch {
    // A missing ConfigMap or a role without read access is not fatal: the upload
    // can still use Halo's default policy.
  }

  const policyName = await resolveUploadPolicyName(icons.policyName);
  if (!policyName) {
    return null;
  }
  const groupName = await resolveUploadGroupName(icons.groupName);
  return groupName ? { policyName, groupName } : { policyName };
}

async function resolveUploadPolicyName(configured: string): Promise<string> {
  // A policy that is explicitly configured but hidden in Halo's upload UI is still
  // valid, which is why existence is checked directly instead of via
  // listStoragePolicyOptions().
  if (configured && (await policyUsable(configured))) {
    return configured;
  }
  try {
    return (await listStoragePolicyOptions())[0]?.value ?? "";
  } catch {
    // Listing failure falls through to Halo's own upload endpoint.
    return "";
  }
}

async function policyUsable(name: string): Promise<boolean> {
  try {
    const { data } = await coreApiClient.storage.policy.getPolicy(
      { name },
      SILENT_REQUEST
    );
    return !data.metadata.deletionTimestamp;
  } catch {
    return false;
  }
}

async function resolveUploadGroupName(configured: string): Promise<string> {
  if (!configured) {
    return "";
  }
  try {
    const { data } = await coreApiClient.storage.group.getGroup(
      { name: configured },
      SILENT_REQUEST
    );
    return data.metadata.deletionTimestamp ? "" : configured;
  } catch {
    // The group was removed after the settings were saved.
    return "";
  }
}
