# 🎮 《Kodee大逃杀》 (Kodee Royale)

俯视角 2D 多人联机大逃杀竞技场网页游戏——满屏技术梗、多房并行、支持账号注册与房间码联机！

---

## 📖 游戏简介

《Kodee大逃杀》是一款基于 Kotlin (Ktor) + Vue 3 (Canvas) 开发的实时大逃杀网页游戏。

- **游戏玩法**：俯视角 2D 竞技场，玩家通过移动、攻击、躲避与击杀怪物（NPE、SOE 等）、拾取 Kotlin/Java 技术梗道具、玩家互殴以及躲避 GC 毒圈收缩，争取活到最后！
- **联机与房间**：支持创建房间/6 位房间码加入、房主添加 Bot 补位对战、断线重连、出局后平滑过渡至自由/跟随观战视角。
- **服务端权威**：采用服务端权威架构，客户端仅负责输入采集与插值渲染，保证对局公平性。

---

## 🎮 操作说明

| 控制方式 | 操作细节 |
| :--- | :--- |
| **PC 桌面端** | • **移动**：`W` `A` `S` `D` 或方向键 `↑` `↓` `←` `→`<br>• **瞄准**：鼠标指针朝向<br>• **开火 / 攻击**：鼠标左键按住 或 `Space` 空格键<br>• **互动 / 表情**：`E` 键<br>• **防误触设计**：鼠标右键菜单已全局禁用；切屏/失焦自动重置方向键输入，防止卡键 |
| **移动端** | • **移动**：左侧虚拟摇杆<br>• **瞄准 & 攻击**：右侧攻击摇杆（推住即瞄准并持续连发） |

---

## 🛠️ 技术栈

- **后端服务端 (`server/`)**：
  - 语言与框架：Kotlin + Ktor 框架 (Netty 引擎)
  - 数据库与持久化：PostgreSQL + Exposed ORM + HikariCP
  - 实时通信：WebSocket 消息信令 + WebRTC 状态同步
- **前端客户端 (`web/`)**：
  - 框架与构建：Vue 3 (Composition API) + TypeScript + Vite
  - UI 库与状态管理：Naive UI + Pinia + Vue Router
  - 渲染引擎：HTML5 Canvas 2D / 伪 2.5D 自研渲染引擎 + 粒子与相机跟随

---

## 📂 项目结构

```text
kotlin-game/                              # 根目录 (E:\VsCode\Web\kotlin-game)
├── README.md                             # 本说明文档 (位于 kotlin-game 根目录)
├── .gitignore                            # 根目录 Git 忽略文件
├── server/                               # Kotlin / Ktor 服务端
│   ├── src/main/kotlin/                  # 服务端源码 (房间、游戏世界、WebRTC/WS 路由)
│   ├── src/main/resources/
│   │   ├── application.default.yaml        # 服务端默认配置模板 (已追踪)
│   │   └── application.yaml                # 运行期本地配置 (Git 忽略)
│   └── build.gradle.kts                  # Gradle 构建配置
└── web/                                  # Vue 3 / Vite 前端客户端
    ├── .env.development                  # 开发环境前端配置 (已追踪，默认 http://localhost:8011)
    ├── .env.production                   # 生产环境前端配置 (Git 忽略，生产部署使用)
    ├── src/
    │   ├── game/                         # 游戏引擎、输入控制(input.ts)、渲染器(renderer.ts)
    │   ├── views/                        # 页面组件 (登录、大厅、房间、对局)
    │   └── stores/                       # Pinia 状态管理
    └── package.json
```

---

## 🚀 部署与运行指南

### 1. 环境准备

- **JDK**: 17 或更高版本
- **Node.js**: 18.x 或更高版本 (推荐使用 npm 或 pnpm)
- **PostgreSQL**: 12.x 或更高版本 (创建数据库例如 `kotlin_game`)

---

### 2. 服务端部署与配置说明 (`server`)

1. **进入服务端目录**：
   ```bash
   cd server
   ```

2. **配置文件机制与默认模板说明**：
   服务端采用了**配置模板与运行时隔离机制**：
   - **默认配置模板 (`src/main/resources/application.default.yaml`)**：由 Git 追踪管理，提供标准默认配置。
   - **运行时配置文件 (`src/main/resources/application.yaml`)**：已被 `.gitignore` 忽略，用于配置生产或本地具体敏感参数（如数据库密码、JWT Secret 等）。
   - **自动复制机制**：首次启动服务端时若检测到不存在 `application.yaml`，系统会自动将 `application.default.yaml` 复制一份作为 `application.yaml` 运行。

   **默认模板配置展示 (`application.default.yaml`)**：
   ```yaml
   ktor:
     deployment:
       port: 8011
     application:
       modules:
         - com.setruth.game.plugins.SerializationKt.configureSerialization
         - com.setruth.game.plugins.CorsKt.configureCORS
         - com.setruth.game.auth.AuthPluginKt.configureAuth
         - com.setruth.game.config.DbKt.configureDb
         - com.setruth.game.net.WsRoutesKt.configureSockets
         - com.setruth.game.HttpRoutesKt.configureHttpRoutes

   app:
     db:
       url: "jdbc:postgresql://localhost:5432/kotlin_game"
       user: "postgres"
       password: "Password01!"
     jwt:
       secret: "dev-secret-change-me"
     webrtc:
       minPort: 50000        # WebRTC UDP 动态端口池起始端口
       maxPort: 50100        # WebRTC UDP 动态端口池结束端口
     game:
       shrinkTime: 15        # 每轮缩圈时长(秒)
       shrinkCooldown: 45    # 缩圈冷却(秒)
       firstIdle: 15         # 首轮缩圈前静置(秒)
       friendlyFire: true    # 玩家互伤开关
       monsterTouchDamage: 25 # 怪物触碰伤害
       monsterInitNpe: 10    # 初始 NPE 怪物数
       monsterInitSoe: 5     # 初始 SOE 怪物数
       monsterWaveNpe: 6     # 每轮新增 NPE 数
       monsterWaveSoe: 3     # 每轮新增 SOE 数
       bombsEnabled: true    # 轰炸区开关
       itemCount: 12         # 场上道具上限
   ```

3. **运行服务端**：
   - **Windows**:
     ```cmd
     .\gradlew.bat run
     ```
   - **Linux / macOS**:
     ```bash
     ./gradlew run
     ```
   - 服务端默认启动于端口 `8011`。

---

### 3. 前端客户端部署与配置说明 (`web`)

1. **环境变量配置说明**：
   前端采用 Vite 的 `.env` 环境变量文件进行后端 API 及 WebSocket 地址绑定：

   - **开发环境配置 (`web/.env.development`)**（默认追踪，适合本地联调）：
     ```ini
     # 后端 API 地址（开发）：默认指向本地 Ktor (端口 8011)，含 /api 前缀
     VITE_API_BASE_URL=http://localhost:8011/api

     # WS 地址（必填）：只写协议与域名/端口，不带路径（/ws 由代码拼接）
     VITE_WS_URL=ws://localhost:8011
     VITE_STUN_URL=''
     ```

   - **生产环境配置 (`web/.env.production`)**（已被 `.gitignore` 忽略，生产部署自行新建配置）：
     ```ini
     # 后端 API 地址（生产示例：同源部署填 /api，跨域部署填写具体 URL）
     VITE_API_BASE_URL=https://your-domain.com/api

     # 生产 WebSocket / WebRTC STUN 服务配置
     VITE_WS_URL=wss://your-domain.com
     VITE_STUN_URL=stun:your-domain.com:53478
     ```

2. **安装依赖**：
   ```bash
   cd web
   npm install
   ```

3. **开发模式运行**：
   ```bash
   npm run dev
   ```
   - 启动后通过浏览器访问终端输出的本地地址（默认 `http://localhost:5173`）。

4. **生产模式打包构建**：
   ```bash
   # 打包生成生产环境构建产物（读取生产环境配置文件）
   npm run build:prod
   ```
   - 构建产物将生成在 `web/dist/` 目录中，可直接部署至 Nginx / Caddy 等静态 HTTP 服务器。

---

## 📝 开发者注意事项 (Git 说明)

- 根目录 `.gitignore` 已配置完整忽略规则，移除了开发设计文档目录 (`design/`) 的提交，同时对服务端的运行时配置文件 `application.yaml` 和前端的生产环境配置文件 `.env.production` 进行保护（不会被 Git 提交到远程仓库）。
- 直接在 `kotlin-game` 根目录创建 Git 仓库并提交代码即可：
  ```bash
  git init
  git add .
  git commit -m "feat: initial commit for Kodee Royale"
  ```
