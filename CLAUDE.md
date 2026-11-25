# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 开发准则（最高优先级）

一切代码开发都必须先参考 Halo 官方文档与官方已有实现，再动手写代码：

- 官方文档：<https://docs.halo.run/developer-guide/>（插件开发、控制台 UI、扩展点、API 参考）。
- 官方代码：本仓库依赖的 `@halo-dev/components`、`@halo-dev/ui-shared`、`@halo-dev/api-client` 对应版本（2.27.x）的源码与样式；文档未覆盖的行为，从本地 `halo.jar` 解出的控制台产物或同版本官方插件中反查真实实现（例如菜单页的拖拽、`he-tree` 占位样式、实体行插槽结构）。
- 不要凭记忆臆造 Halo 的 API、组件插槽、CSS 类名、配置字段或扩展点；先查文档或源码确认，再写代码。
- 文档与既有代码冲突时以官方文档为准；文档未提及的细节以同版本官方代码的实际行为为准，并在代码注释中写明依据。

## 项目定位

Halo 2.x 插件（要求 >= 2.27.0，JDK 21），提供独立的导航页与配套控制台管理界面：

- 前台独立页面 `/nav`（Thymeleaf 模板 + 原生 ES5 静态资源，不依赖 Halo 主题）
- 控制台管理页 `/console/palette-nav`（Halo 控制台 ESM 插件，Vue 3）

六个自定义模型（`palette-nav.halo.run/v1alpha1`）：`NavCategory`、`NavLink`、`NavSearchEngine`、`NavQuickLink`，以及只供服务端使用的 `NavVisitStat`（每日 PV/UV 与当日随机盐）和 `NavVisitVisitor`（每位访客一个资源，按名称冲突做当天 UV 去重，次日清理）。

## 常用命令

```bash
pnpm -C ui install            # 前端依赖
pnpm -C ui type-check         # vue-tsc 类型检查（前端唯一自动化门禁）
pnpm -C ui dev                # vite build --watch，输出 ui/build/dist

# 从运行中的 Halo 重新生成 ui/src/api/generated（模型 + API 类）
./gradlew generateApiClient -PhaloOpenApiUseExistingServer=true -PhaloExternalUrl=http://localhost:8095

./gradlew test                # JUnit（纯单元测试，不需要 Halo 运行时）
./gradlew test --tests "cn.litx.palettenav.service.NavIconRendererTest"
./gradlew classes             # 供开发模式使用的 build/classes、build/resources
./gradlew build               # 类型检查 + 测试 + UI 打包校验 + 生成 JAR
./gradlew build -x test       # 跳过测试的快速构建

# 从运行中的 8095 实例重新导出内置默认数据（分类/链接/搜索引擎/快捷导航，不含访问统计）
python tools/export-nav-defaults.py
```

产物：`build/libs/palette-nav-<version>.jar`。根项目 `check` 会连带执行 `:ui:check`（即 `type-check`），`processUiResources` 把 `ui/build/dist` 同步到 `build/resources/main/ui`，因此只改前端后要让 Halo 开发实例生效需重跑 `./gradlew classes`。

### 本地 8095 调试

见 `README.md`：复制 `halo.jar` 到 `.tools/halo/`，复制 `scripts/application.example.yaml` 为 `.tools/halo/application.yaml` 并替换绝对路径，然后 `./gradlew classes` + `scripts/dev.sh`（或 `scripts/dev.bat`）。`dev.sh` 会校验 JDK 21，并在 Halo 就绪后通过本地 basic auth 自动把 `palette-nav` 的 Plugin CR 置为 `spec.enabled=true`（Halo 是否启动插件以该字段为准，`auto-start-plugin` 不可靠）；`dev.bat` 只做 JDK 校验，不自动启用。首次初始化后本地账号 `admin` / `admin123`，插件启用后访问 `/nav` 与 `/console/palette-nav`。`.tools/` 已被 gitignore，仅本机运行时数据。

`tools/` 下的 Playwright 脚本（`test_palette_nav.py`、`verify_icon_picker.py`、`verify_dashboard.py`）依赖 8095 实例在跑；`export-nav-defaults.py` 从 8095 导出 `data/nav-defaults.json`；`encrypt-login.mjs` 是一次性开发辅助，不参与插件运行时。

## 架构

### 后端（`src/main/java/cn/litx/palettenav/`）

- `PaletteNavPlugin`：`BasePlugin` 入口，start/stop 时向 `SchemeManager` 注册/注销六个模型。`@GVK` 仅是元数据，不注册就没有 CRUD API。
- 模型自动 CRUD：`/apis/palette-nav.halo.run/v1alpha1/{navcategories|navlinks|navsearchengines|navquicklinks}`，由 Halo 扩展存储提供，本项目不写 Controller。
- `endpoint/NavConsoleEndpoint`：仅控制台使用的自定义 API（`console.api.palette-nav.halo.run/v1alpha1`）：`POST /reorder`、`DELETE /category/{name}`（级联删除分类下链接）、`POST /link-meta`（读取目标网页名称/图标，见 `service/NavLinkMetaService`；图标由 `service/NavIconStorageService` 下载并转存为 Halo 附件，返回附件 permalink，失败时回退原图标地址）、`GET /stats`（仪表盘数据，见 `service/NavStatsService`）。对应权限规则在 `extensions/role-templates.yaml`，UI 权限标识 `plugin:palette-nav:manage`。
- `web/NavPageRouter`：公开页面 `GET /nav`，`NavQueryService.loadVisiblePageData` 过滤 `visible`、孤儿分类、非法 URL，`NavIconRenderer` 把图标转成安全 HTML，注入 Thymeleaf 模型渲染 `templates/palette-nav.html`；每次成功渲染会向 `NavStatsService` 异步记录一次 PV/UV（`enableVisitStats` 关闭时跳过）。
- `service/NavPublicViewService`：`/nav` 与公开 JSON 的**唯一数据源**，集中计算 logo/品牌文案/背景合并/备案数字/页脚净化/雪碧图等派生值。`NavPageRouter` 只把 `NavPublicView` 映射成模板使用的扁平模型 key；修改展示派生规则必须改这里，保证 HTML 与 JSON 不漂移。
- `endpoint/NavPublicEndpoint`：公开只读 JSON `GET /apis/api.palette-nav.halo.run/v1alpha1/nav`（组 `api.palette-nav.halo.run`），返回 `NavPublicView`（四类导航数据 + site/appearance/search/footer + `contextPath`），不含 `trustedProxyIps`、`enableVisitStats`、附件存储策略与 PV/UV；调用不计入访问统计。匿名与登录用户均可读：`role-templates.yaml` 中的 `palette-nav-role-public` 同时聚合到 `anonymous` 与 `authenticated`，只授予 `nav` 的 `get`/`list`。负载内所有站内地址保持根相对路径、由顶层 `contextPath` 统一拼接（不要像 `/nav` 那样预先拼 context path）；`searchEngines[].mobileSearchUrl` 供服务端调用方自行选择模板；结果按（移动/桌面 UA + contextPath）缓存 30 秒。该组已加入 `build.gradle` 的 OpenAPI `pathsToMatch`，修改 `NavPublicView` 后需重新运行 `generateApiClient`。公开页只内联当前数据实际引用到的雪碧图符号（`service/NavIconSpriteService` 启动后解析一次 `static/nav/vendor/palette-icons.js`），不加载完整雪碧图；控制台预览仍按需加载该文件。雪碧图是自制图标，默认分类使用彩色 `pn-cat-*` logo，链接使用单色 `pn-*` 线条图标；旧 6 个默认分类 id 在渲染时映射为对应分类 logo，其余 `lyicon-*`/`icon-*` 引用回退为通用链接图标。不要重新引入第三方图标集。
- `service/NavStatsService`：PV/UV 先在内存累计，每 5 秒批量写入当天的 `NavVisitStat`（失败会把增量放回下次重试，插件停止时做最后一次 flush）；访客去重仍按天创建 `NavVisitVisitor`，过期访客清理使用 `palette-nav.halo.run/visit-date` 标签索引，不做全表谓词扫描。
- `extensions/reverse-proxy.yaml`：把 `/plugins/palette-nav/assets/nav/**` 映射到 `static/nav/`，前台资源从这里取。
- `config/`：`NavSettings` 定义四组设置（general/appearance/search/icons）及 `normalized()` 兜底；`icons` 指定图标转存以及控制台「上传」（图标 / LOGO / 背景）使用的存储策略与附件分组，留空时由 `NavIconStorageService` / `resolveAttachmentUploadTarget()` 选择上传界面中优先级最高（`storage.halo.run/policy-priority-in-upload-ui`）且未隐藏的策略，分组留空表示不分组；`NavSettingsService` 读取失败时永远回退默认值。设置表单已从插件详情页移到 `/console/palette-nav` 的「设置」页签（`components/SettingsTab.vue` + `api/settings.ts`），通过 Halo 核心 ConfigMap API（`/api/v1alpha1/configmaps/palette-nav-config`）读写独立的 `palette-nav-config` ConfigMap。插件刻意不声明 `settingName` **也不声明 `configMapName`**：Halo 的「卸载并删除配置」会删除 `spec.configMapName` / `spec.settingName` 指向的资源，而导航设置属于业务数据，必须在卸载/重装后保留。因此 `NavSettingsConfigInitializer` 在每次插件启动时负责创建缺失的 ConfigMap，`NavSettingsService` 直接按固定名称读取（不再使用 `ReactiveSettingFetcher`）。插件详情页没有设置入口，Halo 的「重置配置」也不再出现；设置页签也不提供恢复默认按钮。版权信息在写入 Thymeleaf 模型前由 `service/NavFooterSanitizer` 做 jsoup 白名单净化；不要再引入可向公开页输出脚本的页脚字段。
- `service/NavConsoleService`：控制台批量操作，`reorder` 按 `kind` 重写 sort，`deleteCategory` 先删分类下全部链接再等分类真正消失（Halo 删除走 finalizer 异步生效）。`service/NavEntitySupport` 统一 alive/visible/sort 排序规则（sort 升序，同值按名称）。
- `service/NavDefaultDataService`：首次启用时导入 `src/main/resources/data/nav-defaults.json`。仅当四类数据全为空且 `palette-nav-defaults-state` ConfigMap 不存在时导入；导入后写标记，因此用户后续修改/删除不会被恢复。不要改用 `extensions/*.yaml` 承载这些内容：Halo 会在每次插件启动时重建缺失项并覆盖同名资源。设置默认值不在此文件中，而是 `NavSettings` 的 `defaults()`（与 `ui/src/api/settings.ts` 的 `DEFAULT_VALUES` 保持同步）；默认数据不含 `NavVisitStat` / `NavVisitVisitor`。

### 控制台前端（`ui/src/`）

- `index.ts`：`definePlugin` 注册路由 `/palette-nav`、菜单（tool 组）、权限与图标。
- `views/PaletteNavView.vue`：页面壳，一次拉取四类数据（分页循环，每页 100），负责 Tab、刷新与预览。
- 首个「仪表盘」页签由 `components/DashboardTab.vue` 渲染（ECharts 按需动态引入，展示链接数量、今日/累计 PV、今日 UV 与近 7 天走势）；其后的四个数据 Tab（`components/*Tab.vue`）各自持有工具栏、弹窗、多选、拖拽排序与分页；`LinkTab` 额外有 `SearchInput`（回车触发）、`FilterDropdown`（只列具体分类，刻意没有「全部链接」）、孤儿链接告警和 `VPagination`。「设置」页签由 `SettingsTab.vue` 渲染四组设置表单（含访问统计开关、附件存储的策略/分组选择），保存走核心 ConfigMap API `PUT /api/v1alpha1/configmaps/palette-nav-config`（保存前重新读取以带上当前 `metadata.version`）。LOGO 与背景使用 `MediaPicker.vue`（上传 / 附件库 / 链接三选一；「上传」经 `useAttachmentUpload` 写入设置页选定的存储策略与分组），背景已合并为 `backgroundUrl` + `backgroundType`，旧配置的 `backgroundImage` / `backgroundVideo` 在读取时自动迁移。
- 共享件：`useDragSort`（Pointer Events 拖动排序，克隆行跟随光标 + 虚线占位，跨分类边界用 `canDrop` 拦截）、`useSortDraft`（拖拽顺序先存草稿，点「保存排序」才 `POST /reorder`；未保存时切 Tab/离开页面会弹确认）、`useBulkVisibility`（批量启停，8 个一批）、`useBulkMove`（多选链接移动到其他分类，改写 `categoryName` 并按目标分类 `nextSort` 追加；已有排序草稿时入口禁用）、`useBulkCopy`（多选链接复制到目标分类，用新的 `metadata.name` 创建副本、按目标分类 `nextSort` 追加；允许复制到原分类，不做同网址去重）、`useBulkDelete`（多选批量删除，`Dialog.warning` + `confirmType: "danger"`，分类走级联删除接口）、`DragHandle`/`DragPlaceholder`/`SelectAllCheckbox`/`IconPicker`/`IconPreview`。
- `api/nav.ts` 通过 `api/generated` 的生成客户端封装 CRUD、排序、级联删除与 link-meta（`N*V1alpha1Api` 类 + `axiosInstance`）；`types/nav.ts` 只做生成模型的别名；`utils/entity.ts` 回写版本号；`utils/reorder.ts` 整表重编号；`utils/icon.ts` 与后端同规则的前端预览。
- `styles/console.css` 是建立在 Halo 宿主令牌（`--colors-primary`、`--borderRadius-base`）之上的 `--pn-*` 轻量层，实体行/拖动样式也在其中。

## 必须遵守的约定

1. **图标与 URL 安全规则前后端各有一份，改动必须同步**：`service/NavUrlValidator.java` + `service/NavIconRenderer.java` ↔ `ui/src/utils/icon.ts`。只允许 http(s) 与站内 `/` 路径（图片/背景不接受页内锚点 `#`）；SVG 只有雪碧图引用 `<svg><use href="#id"/></svg>` 可作标记输出，其余 SVG 一律转 base64 data URL，含 `<script>`/`on*=`/`javascript:` 的输入回退默认图标。前台模板用 `th:utext` 输出图标，不要放松这层过滤。
2. **每次更新实体都要回写 `metadata.version`**（`utils/entity.ts#syncEntityVersion`），包括批量启停与单行开关，否则下一次保存会 409「检测到冲突」。
3. **排序先存本地草稿，点「保存排序」才提交**：拖拽只通过 `useSortDraft` 改渲染顺序，不立即发请求；保存时 `sortUpdatesForOrder` 把目标列表按 10 递增重排后 `POST /reorder {kind, items}`（链接按每个受影响的分类分别重编号）。不要写单条 sort 交换；历史数据的 sort 可能全为 0，重编号必须覆盖整表。`kind` 用模型 Kind 名（如 `NavLink`）。未保存时切换 Tab / 离开页面必须确认，避免静默丢弃。
4. **Halo 控制台组件约束**：`VEntity` 渲染 `<tr>`，只能放在 `VEntityContainer` 内；`VEmpty`/`VAlert`/`VLoading` 必须与它同级，不能塞进 `<tbody>`；`VCard` 一旦使用 `#header`，`#actions` 不渲染；`VModal #footer` 无 flex，右对齐需包 `.pn-modal-footer`。`FormKit`/`SearchInput`/`FilterDropdown` 是宿主全局组件，本地只在 `types/global-components.d.ts` 里声明为 `any`，不要从 `@halo-dev/components` 导入。
5. **新增控制台 API 必须同时补 `role-templates.yaml` 规则**，否则非超级管理员角色调用会 403。该文件现在是多文档 YAML（管理角色 + 公开角色），读取它的测试必须用 `Yaml.loadAll` 并按 `metadata.name` 取角色。公开 API 的角色要同时打 `rbac.authorization.halo.run/aggregate-to-anonymous` 与 `aggregate-to-authenticated` 标签，否则未登录访客可读、已登录用户却会 403；`GET` 集合请求在 Halo 中判定为 `list`，verbs 必须包含 `list`。
6. **UI 产物必须是 ESM**：`:ui:verifyUiBundle` 会校验 `ui-plugin.json` 里的 `esm` 入口存在，`build` 会失败。不要把入口改成 CJS。
7. **TS 开启 `noUncheckedIndexedAccess`**：数组下标访问返回 `T | undefined`，且 `VAlert theme`、`VTag theme`、`VButton type` 是字面量联合类型，动态绑定需显式标注。
8. **前台 `/nav` 保持无构建链的原生 ES5 + 静态资源**（`static/nav/`）。只做与现有 DOM 契约一致的增量修改（`script.js` 顶部注释记录了依赖的 id/data-*），不要引入框架、打包器或 Halo 主题依赖。
9. **控制台 UI 视觉对齐 Halo 2.27**：优先用宿主组件与令牌，不要自造一套颜色/圆角；新增样式沿用 `console.css` 的 `--pn-*` 变量。插件 CSS 由 Halo 全局注入，凡是选择宿主组件类（如 `.entity-field-wrapper`、`.entity-wrapper`、`.entity-end`）的规则必须限定在 `.pn-page` 作用域内，否则会改变文章、页面、附件等核心列表的布局；拖拽 ghost 挂在 `<body>` 上，需要单独用 `.pn-drag-ghost` 作用域。
10. **本地默认账号 `admin`/`admin123` 仅用于开发**；对外分发或部署前提醒更换强密码。
11. **API 类型以 `generateApiClient` 生成为准**：`ui/src/api/generated` 是生成产物，不要手改；后端 DTO / 自定义端点变更后重新生成。自定义端点必须用 `SpringdocRouteBuilder` 声明 operationId、请求体和响应，否则不会进入生成的 Client；不要在 UI 里手写 `/apis/...` 路径或资源模型。
12. **设置字段与默认值有两份，必须同步**：`ui/src/api/settings.ts` 的 `NavSettingsValues` / `DEFAULT_VALUES` ↔ `config/NavSettings.java` 的四组字段、`defaults()` 与 `normalized()`；背景媒体的扩展名识别规则在 `utils/media.ts` ↔ `service/NavMediaType.java` 各有一份。插件不声明 Halo Setting（`extensions/` 下没有 settings.yaml），也没有重置入口；未保存的字段按 `normalized()` 回退默认值。设置写入独立的 `palette-nav-config` ConfigMap，且插件不声明 `spec.configMapName`（否则「卸载并删除配置」会删掉业务设置）；get/update 权限按 `apiGroups: [""] / configmaps / resourceNames: palette-nav-config` 限定；**不要为管理角色声明不带 `resourceNames` 的 `configmaps create`**，ConfigMap 由 `NavSettingsConfigInitializer` 在插件启动时创建，设置页在缺失时只提示重启插件。`NavRoleTemplateTest` 会校验这条约束。
13. **链接列表只能按具体分类查询，禁止提供「全部链接」入口**：`LinkTab` 的 `FilterDropdown` 只列真实分类（另加「分类缺失」孤儿项用于修复数据），筛选值始终是某个具体分类的 `metadata.name`，没有 `all` 之类的伪选项；筛选失效时回退到第一个可用分类，没有任何分类时展示空状态，绝不能回退成全量查询。不要以「方便查找」为由加回「全部链接」或跨分类全量视图。
