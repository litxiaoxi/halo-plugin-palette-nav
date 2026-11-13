# Palette Nav 上网导航

独立运行的 Halo 2.x 上网导航插件。

- 前台独立页面：`/nav`
- 控制台管理页：`/console/palette-nav`
- 不依赖 Halo 官方「链接管理」插件

## 功能

- 分类、链接、搜索引擎、快捷导航的完整管理
- 6 种配色与夜间模式
- 时钟、日期、问候语、随机一言（本地文本）
- 多搜索引擎跳转、百度搜索建议（可关闭）
- 分类筛选、背景图片/视频切换、快捷导航菜单
- 响应式布局（8 / 6 / 4 / 3 列）
- 控制台仪表盘：链接数量、今日/累计浏览量、近 7 天 PV/UV 走势（服务端采集，可在设置中关闭）
- 内置默认导航数据：在没有任何导航数据的新实例上首次启用时自动导入分组、链接、搜索引擎与快捷导航；之后用户的修改和删除不会被恢复。默认数据不含访问统计

## 截图

### 六种主题色

| 紫色 | 蓝色 | 绿色 |
| :---: | :---: | :---: |
| [![紫色主题](docs/screenshots/nav-theme-purple.png)](docs/screenshots/nav-theme-purple.png) | [![蓝色主题](docs/screenshots/nav-theme-blue.png)](docs/screenshots/nav-theme-blue.png) | [![绿色主题](docs/screenshots/nav-theme-green.png)](docs/screenshots/nav-theme-green.png) |

| 粉色 | 橙色 | 青色 |
| :---: | :---: | :---: |
| [![粉色主题](docs/screenshots/nav-theme-pink.png)](docs/screenshots/nav-theme-pink.png) | [![橙色主题](docs/screenshots/nav-theme-orange.png)](docs/screenshots/nav-theme-orange.png) | [![青色主题](docs/screenshots/nav-theme-teal.png)](docs/screenshots/nav-theme-teal.png) |

### 背景图片与搜索引擎切换

| 背景图片（紫色） | 背景图片（蓝色） |
| :---: | :---: |
| [![背景图片紫色主题](docs/screenshots/nav-background-purple.png)](docs/screenshots/nav-background-purple.png) | [![背景图片蓝色主题](docs/screenshots/nav-background-blue.png)](docs/screenshots/nav-background-blue.png) |

[![切换搜索引擎](docs/screenshots/nav-search-engine.png)](docs/screenshots/nav-search-engine.png)

### 夜间模式

|                             紫色                             |                             蓝色                             |
| :----------------------------------------------------------: | :----------------------------------------------------------: |
| [![夜间模式紫色](docs/screenshots/nav-dark-purple.png)](docs/screenshots/nav-dark-purple.png) | [![夜间模式蓝色](docs/screenshots/nav-dark-blue.png)](docs/screenshots/nav-dark-blue.png) |



## 环境要求

- Halo **>= 2.27.0**
- JDK 21
- Node.js >= 22.12、pnpm 10

## 开发与构建

```bash
# 前端依赖
pnpm -C ui install

# 构建插件 JAR
./gradlew build
```

产物：`build/libs/palette-nav-<version>.jar`，可直接在 Halo 控制台「插件 → 安装」上传。

### 本地 8095 调试（无 Docker）

1. 复制 `halo.jar` 到 `.tools/halo/`（本机可从相邻项目复制 Halo 2.27.2）。
2. 复制 `scripts/application.example.yaml` 到 `.tools/halo/application.yaml`，把其中 `D:/path/to/...` 改成当前项目绝对路径。配置已固定端口 `8095`、H2 文件库与开发模式。
3. 准备运行时资源：

   ```bash
   ./gradlew classes
   ```

4. 启动 Halo：

   ```bash
   ./scripts/dev.sh
   ```

   脚本会校验 JDK 21（可用 `HALO_JAVA_HOME=/path/to/jdk-21` 指定），并在 Halo 就绪后通过本地 basic auth 自动启用 `palette-nav` 插件。若当前 `JAVA_HOME` 不是 21，请设置 `HALO_JAVA_HOME`。Windows 也可用 `scripts\dev.bat`（该脚本不会自动启用插件）。

5. 首次访问 `http://localhost:8095/system/setup` 完成初始化（本地示例：`admin` / `admin123`）。
6. 使用 `scripts/dev.sh` 时插件会自动启用；用 `dev.bat` 或其他方式启动时，登录控制台在「插件」中启用 `Palette Nav 上网导航`，然后访问 `/nav`。
7. 进入 `/console/palette-nav`，添加分类、链接、搜索引擎与快捷导航。

`halo { port = 8095 }` 同时写入 `build.gradle`，有 Docker 环境时可直接使用 `./gradlew haloServer`。

## 插件设置

设置已合并到控制台 `/console/palette-nav` 的「设置」页签。插件不声明 Halo Setting，因此插件详情页没有设置入口，插件列表的「重置配置」也不会出现。没有保存过设置的新实例会使用内置默认值（站点名称、描述、关键词、品牌文字、紫色主题等）。共五组设置：

- **基本设置**：标题、描述、关键词、LOGO（支持上传 / 附件库 / 链接）、品牌文字、版权、ICP 备案号与图标、公安备案号与图标（图标留空时页脚只显示备案号文字）
- **访问统计**：访问统计开关（`enableVisitStats`）、页脚统计显示开关（`showFooterVisitStats`）与可信反向代理地址（`trustedProxyIps`）。记录开关关闭后不再记录新的访问，历史数据仍会展示；页脚开关独立控制是否在备案信息上方展示今日浏览、今日访客与累计浏览，默认关闭。只有来自本机回环地址或 `trustedProxyIps` 的 `X-Forwarded-For` / `X-Real-IP` 才会被采信，留空表示只信任本机代理。多级代理（CDN → nginx → Halo）需要逐一填写每一跳代理地址才能取到真实访客 IP
- **外观设置**：默认主题色、默认明暗模式、时钟、一言、导航栏显示分类图标开关、背景默认显示开关、背景媒体（图片或视频，支持上传 / 附件库 / 链接）。「导航栏显示分类图标」默认关闭，分类导航条只显示分类名称。「背景默认显示」关闭时首次访问只显示主题渐变，访客点击右上角背景按钮后才显示；开启后首次访问即显示背景，访客自己的切换选择仍会记住并优先
- **附件存储**：新增/编辑链接点击「获取」时，网站图标会先转存到 Halo 附件再引用；控制台通过「上传」添加的图标、LOGO 与背景也会存入这里指定的存储策略与附件分组。留空时使用上传界面中优先级最高的存储策略、不分组
- **搜索设置**：百度搜索建议开关（默认开启）

图标字段支持控制台内直接上传图片/SVG 文件、从 Halo 附件库选择、填写图片地址或粘贴 SVG 代码，底层兼容三种存储形式：

1. SVG 雪碧图引用（`<svg><use xlink:href="#pn-x"></use></svg>`，可用图标见 `palette-icons.js`；分类默认使用彩色 `pn-cat-*` logo，链接使用 `pn-*` 线条图标；旧版 6 个默认分类 id 会映射到对应分类 logo，其余 `lyicon-*`/`icon-*` 引用显示为通用链接图标）
2. 完整 SVG 代码（渲染时转为 base64 data URL 图片，脚本不会执行）
3. `http(s)://` 或站内 `/...` 图片地址（上传的文件会写入附件库）

新增/编辑链接时点击网址右侧的「获取」，会自动读取网页名称与图标；图标会下载并转存为 Halo 附件，表单保存的是附件地址，不再直接热链接目标站点。转存失败（例如目标站点拒绝访问或图标格式不受存储策略支持）时回退为原图标地址。目标网页无法读取时接口仍返回 200 并带 `fetched=false`，名称与图标按域名回退；该操作会写入附件，因此接口使用 POST。

## 安装、升级与卸载

- **安装**：控制台「插件 → 安装」，上传 `palette-nav-<version>.jar` 后启用。首次启用且四类导航数据都为空时，会导入一次内置默认数据；之后即使清空数据也不会再次导入（除非手动删除 `palette-nav-defaults-state` ConfigMap）。
- **升级**：在插件列表使用「升级」上传新版本 JAR。已创建的导航数据、访问统计与 `palette-nav-config` 设置都会保留。Windows 下如果在线升级提示文件被占用（运行中的 JVM 锁住了旧 JAR），请先停用插件或停止 Halo，替换工作目录 `plugins/palette-nav-*.jar` 后重新启动。
- **卸载**：在插件列表卸载。Halo 不会删除插件创建的自定义资源；设置保存在独立的 `palette-nav-config` ConfigMap 中，插件未把它声明为 `spec.configMapName`，因此即使勾选「卸载并删除配置」也不会删除这些业务设置。重新安装同版本插件后分类、链接、搜索引擎、快捷导航、访问统计和设置都会恢复；如需彻底清理，请在卸载前删除业务数据，或直接清理 `palette-nav.halo.run` 命名空间下的资源与 `palette-nav-config`、`palette-nav-defaults-state`。

## 权限

插件只申请完成自身功能所需的权限，不读写其他插件或系统配置：

- `plugin:palette-nav:manage`：访问 `/console/palette-nav`，管理四类导航数据、拖拽排序、分类级联删除、读取仪表盘，以及读写本插件设置（`configmaps` 的 `get`/`update` 通过 `resourceNames` 限定为 `palette-nav-config`，没有创建任意 ConfigMap 的权限）。
- 附件相关：上传图标/背景文件并读取 Halo 附件库（`attachments` 读取与上传、存储策略/分组读取）。
- `/nav` 是公开页面，不需要登录或额外权限。

## 隐私与第三方服务

- **访问统计（默认开启，可在设置中关闭）**：`/nav` 每次成功渲染会增加一次浏览量（PV）。独立访客（UV）按「当天随机盐 + 客户端 IP + User-Agent」的截断 SHA-256 哈希按天去重，**不保存原始 IP 或 User-Agent**；盐每天更换，不同日期无法关联同一访客。每位访客的 `NavVisitVisitor` 记录保留当天与前一天（用于覆盖跨零点请求和轻微时钟漂移），更早的记录会在下次清理时删除；每日聚合的 PV/UV 计数（`NavVisitStat`）会长期保留以展示仪表盘。关闭统计后不再记录新访问，历史数据仍会展示。
- **反向代理与真实 IP**：只有来自本机回环地址或设置中 `trustedProxyIps` 的 `X-Forwarded-For` / `X-Real-IP` 会被采信，其余请求一律使用 TCP 对端地址，避免访客伪造 IP 刷高 UV。多级代理（CDN → nginx → Halo）需要列出每一跳代理地址。
- **百度搜索建议（默认开启，可在设置中关闭）**：开启时，搜索框在输入过程中会把当前关键词发送到 `suggestion.baidu.com` 以获取联想词；关闭后不再发起该请求。
- **目标网页抓取**：「获取」按钮只在管理员点击时访问目标网页，用于读取标题与图标；访客访问 `/nav` 不会触发对目标站点的抓取。转存的图标会写入 Halo 附件。
- 除上述情况外，插件不向第三方发送遥测数据。导航卡片与快捷导航指向的站点由管理员配置，访客点击后会跳转到相应站点，其隐私政策由该站点负责。

## 安全说明

- 链接与图片地址只接受 `http/https` 或站内 `/` 路径；`javascript:`、`data:`、协议相对 URL 会被拒绝，图片/背景不接受页内锚点 `#`。
- 图标在服务端经过白名单过滤后再输出，控制台预览使用同一套规则。
- 从目标网页抓取的图标只接受 PNG/JPEG/GIF/WebP/ICO/BMP/AVIF 的魔数校验结果；SVG 不会转存为附件（Halo 内联展示附件时可能执行其中的脚本），而是保留原地址并通过 `<img>` 引用。
- `/nav` 为公开页面；排序与分类级联删除接口仅对拥有 `plugin:palette-nav:manage` 权限的角色开放。
- 「获取」使用插件内置的出站客户端：先解析并校验目标地址，再连接已校验的 IP（防 DNS rebinding），逐跳校验重定向，限制响应大小与超时，并按文件魔数识别图片。为了固定连接地址，它不经过 Halo/系统的 HTTP 代理与自定义 CA 配置；若部署环境必须经代理访问外网，需要额外改造。
- 出站目标默认拒绝 loopback、link-local、multicast、RFC1918 私有网段、CGNAT 与文档/保留网段，不提供内网白名单。内网站点仍可手动填写名称与图标，只是「获取」不会抓取内网页面。
- 版权信息在服务端经过 jsoup 白名单净化后再输出到 `/nav`：仅保留链接、加粗、斜体等文本级 HTML，`script`、事件属性、`style`、`iframe` 等会被移除；「自定义页脚代码」字段已移除。拥有 `plugin:palette-nav:manage` 的角色无法通过设置页向公开页面注入脚本。

## 目录

```
src/main/java/cn/litx/palettenav/   后端
src/main/resources/templates/       前台 Thymeleaf 模板
src/main/resources/static/nav/      前台静态资源
ui/                                 控制台 ESM 前端
tools/                              开发期数据转换与测试脚本
src/main/resources/data/            内置默认导航数据（nav-defaults.json）
docs/screenshots/                   README 截图
```

## 支持与反馈

- 问题反馈：<https://github.com/litxiaoxi/halo-plugin-palette-nav/issues>
- 源码与更新日志：<https://github.com/litxiaoxi/halo-plugin-palette-nav>

## License

MIT。

插件内的图标、默认导航数据、背景图与 LOGO 均为本项目自制或已取得授权；不再随包分发第三方图标集。默认导航数据中的品牌名称与网址仅用于指向对应服务，相关商标归各权利人所有。详见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。
