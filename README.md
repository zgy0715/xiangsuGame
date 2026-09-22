# 像素填空 (Fill-a-Pix) · 康思谜题联网版

> 逻辑与像素的碰撞 —— 基于数字线索推理的康思像素画解谜游戏
> **Android 版**(Kotlin + Compose)与 **鸿蒙版**(ArkTS + ArkUI)均已完成,
> 两端**共用同一个服务端与同一套协议**,可同房对战

Fill-a-Pix(像素填空)是康思谜题(Conceptis Puzzles)旗下的经典逻辑谜题:棋盘上的每个数字提示告诉你以它为中心的 3×3 区域内需要涂黑的格子数量,玩家通过逻辑推理确定每个格子的状态,最终拼出一幅完整的像素画。

**Kotlin + Jetpack Compose**(Android) + **ArkTS + ArkUI**(HarmonyOS) + **Python FastAPI + SQLite**(服务端)。两端逻辑层都是"纯逻辑、无 UI 依赖"的同构实现,可在各自平台直接单测;关卡由**服务端即时生成并校验唯一解**(算法与 `tools/` 工具链共用同一 Python 引擎)。

## ✨ 功能特性

### 账号与登录
- **游客/离线入口**:不登录也能直接进入游戏玩全部内置关,完全离线可用;连不上服务器时也能以游客身份兜底,首页「切换账号」随时转正登录真实账号
- **无用户名密码 —— 邮箱 + 6 位验证码**:输入邮箱 → 服务端生成验证码并 SMTP 发信 → 填码校验通过后签发 Bearer token;首次登录自动注册账号,并引导设置昵称
- **验证码安全策略**:只存 sha256 哈希(不落明文)、10 分钟过期、**单次使用**(用后即焚)、错误 5 次作废、同一邮箱 60 秒重发间隔 + 每小时/每天发送上限
- **发信双通道**:配置 `XIANGSU_SMTP_*` 即真实发信(QQ邮箱/163 授权码);未配置或发信失败自动回退为**服务端控制台打印验证码**,保证开发与答辩现场一定能登录
- **账号体系**:进度/成绩跟账号走;同设备多账号隔离;会话持久化,冷启动自动续登
- **每日登录奖励**:一键补齐额度每日 +2(上限 6),同日重复登录不重复发放
- **昵称管理**:首次登录引导设置,设置页可随时修改,提交服务端持久化

### 游戏从网络获取(服务端即时生成,每题校验唯一解)
- **每日一题**:按日历日确定性生成,全服同题,解开上榜(单题排行榜)
- **在线题库**:按难度分档(简单/中等/困难,8~20 格随机尺寸),拉取即玩
- **离线兜底**:玩过的在线关本地缓存,断网可重玩;10 个内置关随时可玩

### 排行榜
- 每道题取**每人最优成绩**排名,显示"我的最佳";对战成绩**由服务器计时**(不可自报),与单人成绩分榜
- 数据存服务端 SQLite(`solve_records`),网络对战结果自动入榜

### 对战(同题竞速:先完整还原图案者胜)
- **网络对战 2~4 人**:服务器建房/加房(4 位房号)→ 房主选题(内置/每日/随机)→ 全员同题开跑;实时互看填涂进度与用时;服务器按接收序判名次并校验答案
- **蓝牙对战双人**:Classic RFCOMM 直连,无需网络与服务器;主机选题发车,胜负由**双端对称校验**(答案掩码比对 + 用时排序),适合同一教室近场开黑
- 对战全程支持实时进度条、完成提示与结算页(🥇🥈🥉)
- **重赛与断线重进**:结算后房主一键「再来一局」回到房内重开;掉线席位保留,宽限期内重连即续局(竞速中未完成者补发题目重画);**计时基于服务器时钟(NTP 校时),名次与用时不接受自报**

### 经典玩法(单人)
- **智能双向涂黑笔 + ✕ 留白笔 + 拖动连涂**(一条笔划 = 一步撤销)
- **显式"检查错误"**:平时零打扰,点击才精确标红真正画错的格子,可一键清除
- **💡 提示下一步**:基于求解器推导确定格,标出当前盘面"纯逻辑即可推出"的下一格(盘面矛盾时引导先检查)
- **自由/限时双模式**、**参考答案**、**一键补齐(额度制)**、**通关撒花揭晓**
- 10 个内置关(红心/笑脸/蝴蝶/苹果/小房子/小鱼/星星/皇冠/月亮/火箭,10×10→28×28),顺序解锁,每题唯一解

### 界面(深夜画室主题)
恒定深色 + 品牌渐变(靛紫/珊瑚/湖水青),Hero 头卡、分段控件、玻璃胶囊贯穿;竖屏手机与宽屏(横屏/平板)响应式布局;对战页有实时玩家竞速条与结算卡。

### 体验
- **背景音乐**:游戏内循环播放 `res/raw/bgm.mp3`(进程内 MediaPlayer,退到后台自动暂停、回来续播),设置页可开关
- **音效**:点击 / 通关 / 检查出错的提示音(SoundPool,低延迟),与背景音乐各自独立开关
- **摇一摇重置棋盘**:加速度传感器触发,带防抖与 1.2 秒提示
- **断网提示**:网络断开时顶部常驻提示条,恢复后自动消失

## 🎮 玩法规则

1. **读提示**:数字 0~9 表示以该格为中心的 3×3 区域必须恰好涂黑 N 格;无提示格不产生约束。
2. **推理涂黑**:涂黑应涂格、用 ✕ 标记确定留白。
3. **完成**:涂黑格与答案完全一致即还原像素画通关(对战模式需全部正确才算完成)。

| 操作 | 效果 |
|------|------|
| ✏️ 涂黑笔单击 | 未定格→涂黑;已黑格→清除(起笔格决定整笔方向) |
| ✕ 留白笔 | 标记"确定留白" |
| 按住拖动 | 沿轨迹连续涂一条,整笔 = 一步撤销 |
| 双指 | 捏合缩放 + 平移(自动取消误涂) |
| 撤销/重做/重置 | 标准操作历史 |
| 🔍 检查错误 | 平时不标错;点击后按答案精确标红,可一键清除 |

## 🏗️ 项目架构

```
├── app/src/main/java/com/example/xiangsugame/            # 安卓端(Kotlin + Compose)
│   ├── MainActivity.kt        # 入口 + 栈式路由 + AppGraph 依赖容器
│   ├── api/                   # Retrofit + OkHttp + kotlinx-serialization
│   │   ├── GameApi.kt         # REST 接口声明(登录/题库/每日/排行榜/建房)
│   │   ├── ApiClient.kt       # 统一 baseUrl、Bearer 注入、错误解析、401 全局登出
│   │   └── dto/Dtos.kt        # Level JSON 契约 + Level↔DTO 双向映射
│   ├── auth/                  # 邮箱验证码登录会话
│   │   ├── AuthManager.kt     # 发码/登录/会话持久化(客户端不生成任何 code)
│   │   └── LoginRules.kt      # 邮箱校验/验证码格式/倒计时(纯逻辑,可单测)
│   ├── data/                  # 账号进度(按 userId 分片)/ 在线关卡缓存 / 题库仓库 / 本机设置
│   ├── battle/                # 对战线:帧协议 + 传输抽象
│   │   ├── BattleProtocol.kt  # 帧协议(网络/蓝牙同构)
│   │   ├── BattleSession.kt   # 与传输无关的竞速状态机
│   │   ├── NetworkBattleTransport.kt   # WebSocket 房(服务器中转)
│   │   └── BluetoothBattleTransport.kt # RFCOMM 直连 + 双端对称仲裁
│   ├── model/                 # 核心逻辑(纯 Kotlin):Level/GameBoard/Validator/
│   │                          #   PuzzleGenerator/PuzzleSolver/Levels/SparseClues
│   ├── sensor/ receiver/ service/  # 摇一摇 / 网络监听 / 音效与背景音乐
│   └── ui/                    # Compose:登录/首页/题库/排行榜/对战大厅/游戏页…
│       ├── ScreenStack.kt     # 导航纯逻辑(下一关/查关)**有单测**
│       └── theme/ Widgets.kt  # 配色 / 共享控件
├── app/src/test/               # 安卓单测 59 项(含 ui/ScreenStackTest 导航冒烟)
│
├── harmony/                    # 鸿蒙端源码(ArkTS + ArkUI,与安卓端同名镜像)
│   ├── ets/
│   │   ├── model/ data/ api/ auth/ battle/ service/ sensor/   # 与安卓端一一对应
│   │   ├── ui/                # 各页面 + BoardView + Widgets + ScreenStack(导航纯逻辑)
│   │   ├── pages/Index.ets     # 根页面:手写页面栈 + 断网条 + 昵称引导 + onBackPress
│   │   └── AppGraph.ets
│   ├── test/                   # 鸿蒙单测 30 项(@ohos/hypium)
│   ├── config/module.json5     # 权限 / deviceTypes 等工程配置
│   ├── scripts/                # 一键脚本(见下文):编译/安装/自检/日志/签名
│   └── dist/                   # 签名产物(gitignore;已签名 HAP + profile)
│
├── server/                     # Python FastAPI + SQLite 服务端(两端共用)
│   ├── main.py                 # 入口(lifespan 建表 + 题库预热)
│   ├── auth.py puzzles.py leaderboard.py rooms.py db.py content.py
│   ├── mailer.py               # 验证码邮件(SMTP 真发 + 控制台兜底)
│   ├── smtp.env.example        # 发信配置模板(真实配置 smtp.env 已被 gitignore)
│   ├── puzzle_engine.py        # 共享谜题引擎(求解器/唯一解/稀疏提示生成)
│   ├── puzzle_art.py           # 随机像素画生成器(可种子复现)
│   ├── generator.py            # 难度/尺寸策略产题 + 唯一解双保险
│   ├── seed_puzzles.py         # 题库预热 CLI
│   └── smoke_test.py           # 纯 assert 冒烟测试(自建临时库,可重复运行)
│
├── scripts/                    # 服务端启动脚本(双击即用)
│   ├── start-server-smtp.bat   # 启动服务端:自检端口 + 读 smtp.env + 打印客户端该填的地址
│   └── start_server_smtp.ps1   #   其实现(UTF-8 BOM,兼容 Windows PowerShell 5.1)
│
└── tools/                      # 工具链(Python)
    ├── gen_clues.py verify_clues.py gen_audio.py   # 内置关提示链 + 音效合成
    ├── gen_harmony_levels.py   # 从 Levels.kt/SparseClues.kt 生成鸿蒙关卡数据(自带唯一解校验)
    ├── sync_harmony.py         # harmony/ → DEVxiangs 同步(自动修正 .ps1/.bat 编码)
    ├── make_harmony_profile.py # 生成鸿蒙签名 Profile(换包名/刷有效期/内嵌证书)
    ├── compare_platforms.py    # 双端符号级机械比对(找"鸿蒙少实现了什么")
    └── audit_buttons.py        # 按钮可用性审计(找"按了没反应"的写法)
```

### 核心算法
- **提示推导**:`clue[r][c]` = 3×3 窗口内涂黑格数。
- **唯一解校验**:回溯 + 约束传播,`maxSolutions=2` 判定唯一;所有关卡(内置/题库/每日/对战选题)必经此关。
- **在线关卡生成**:随机像素画(矩形/椭圆/三角叠加)→ 贪心去稀疏提示 → 唯一解校验 → 入库。每日一题按日期确定性种子,同一天全服同题。
- **对战判定**:提交棋盘仅比对**涂黑掩码**(与 `Validator.isSolved` 同构);网络对局名次 = 服务器接收序,时长 = 服务器计时;蓝牙对局由双方本地对称校验。

### 服务端部署与演示

```bash
cd server
pip install -r requirements.txt          # fastapi + uvicorn
python seed_puzzles.py                   # (可选)先预热题库,演示更流畅
python -m uvicorn main:app --host 0.0.0.0 --port 8000
python smoke_test.py                     # 服务端冒烟测试(临时库)
```

登录发信配置(全部走环境变量,不配也能跑 —— 验证码会打印在服务端控制台)。

**推荐做法:配置一次,以后双击 `scripts/start-server-smtp.bat` 启动**(它从 `server/smtp.env` 读配置,授权码不进版本库):

```
1) 复制 server\smtp.env.example 为 server\smtp.env
2) 打开 smtp.env,填入邮箱与「授权码」(QQ邮箱:设置 → 账户 → 开启 SMTP 服务 → 短信验证后获得)
3) 双击 scripts\start-server-smtp.bat          # 默认 8000 端口
   scripts\start-server-smtp.bat 8123          # 也可以指定端口
```

脚本会依次做四件事,**不需要你记任何命令**:

1. **检查端口占用** —— 8000 被别的进程占着会问你要不要清掉(避免"改了代码却没生效");
2. 读取 `server/smtp.env`,空值跳过,启动时打印**脱敏后**的生效配置;
3. **打印客户端该填的服务器地址**(本机所有局域网 IPv4 × 端口),安卓/鸿蒙照抄进设置页即可;
4. 在 `0.0.0.0:<端口>` 上起 FastAPI。

首次运行前先装依赖:`cd server && pip install -r requirements.txt`。

启动时的输出长这样(授权码自动脱敏,只显示首字符):

```
========================================
  PixelGame Server (SMTP enabled)
  Port: 8000
========================================

[config] loaded ...\server\smtp.env (7 keys)
[config] SMTP_HOST = smtp.qq.com
[config] SMTP_PORT = 465  (SSL = 1)
[config] SMTP_PASS = z***************
[config] ECHO_CODE = 1  (demo mode: the app shows the code itself)
[ok] SMTP looks configured - verification emails will be sent for real

[client] fill one of these into the app (Settings / Server):
         http://192.168.43.50:8000/
```

> ⚠️ `server/smtp.env` 已在 `.gitignore` 中,请勿把它提交到仓库,也不要把授权码硬编码进代码
> —— 授权码的权限等同于邮箱密码。它只用于启动服务端进程的环境变量。

手动启动(等价于上面的脚本):

```bash
# QQ邮箱:开启 SMTP 服务后拿到「授权码」(不是登录密码)
set XIANGSU_SMTP_HOST=smtp.qq.com
set XIANGSU_SMTP_PORT=465
set XIANGSU_SMTP_USER=你的QQ邮箱@qq.com
set XIANGSU_SMTP_PASS=邮箱授权码
python -m uvicorn main:app --host 0.0.0.0 --port 8000
```

| 环境变量 | 默认值 | 说明 |
|----------|--------|------|
| `XIANGSU_SMTP_HOST/PORT/SSL` | 空 / 465 / 1 | SMTP 服务器;留空即走控制台兜底 |
| `XIANGSU_SMTP_USER/PASS` | 空 | 邮箱账号 + 授权码 |
| `XIANGSU_SMTP_FROM/NAME` | =USER / 像素填空 | 发件人与显示名 |
| `XIANGSU_OTP_TTL_SECONDS` | 600 | 验证码有效期(10 分钟) |
| `XIANGSU_OTP_RESEND_SECONDS` | 60 | 同一邮箱重发间隔 |
| `XIANGSU_OTP_MAX_SENDS_HOUR/DAY` | 5 / 15 | 发送频率上限 |
| `XIANGSU_OTP_MAX_ATTEMPTS` | 5 | 单码允许的失败次数 |
| `XIANGSU_ECHO_CODE` | 0 | 1 = 响应里回显 devCode,登录页直接显示验证码(仅演示用,勿在生产开启) |

**怎么判断配好了没有**:启动后访问 `http://127.0.0.1:8000/`,返回里的 `"smtp"` 为 `true` 即表示已具备真实发信条件;
发码接口返回的 `delivered: true` 表示邮件确实投递成功,`false` 则说明已回退到控制台打印。

**拿到验证码的三种途径**(按可靠性) —— ① 真实邮箱(需配 SMTP);② 服务端控制台(未配 SMTP 时的兜底);
③ 登录页直接显示(`XIANGSU_ECHO_CODE=1`,答辩现场最省事)。

> **启动脚本的两个坑(已修,记下来免得再踩)**:
> ① `smtp.env` 是 UTF-8 **无 BOM**,Windows PowerShell 5.1 的 `Get-Content` 会按系统 ANSI
> (中文机器上是 GBK)解码 —— 中文注释变乱码、行尾残留 `\r` 还会让 `#` 判断失真,导致配置行被整行吞掉。
> 所以脚本改用 `[System.IO.File]::ReadAllLines($path, UTF8)` 显式按 UTF-8 读。
> ② **脚本本身必须存成 UTF-8 with BOM**:PS 5.1 读无 BOM 的 UTF-8 `.ps1` 同样按 GBK 解析,
> 非 ASCII 字符(如破折号 `——`)会造成语法错误。改完脚本用
> `[System.Management.Automation.Language.Parser]::ParseFile()` 先验证语法。

- 默认地址已指向开发机局域网 IP `http://192.168.43.50:8000/`,雷电(跑在宿主机上)与真机连同一热点时都可直连;
- 换网络后:App「设置」把地址改成电脑当前的局域网 IP(`ipconfig` 查),Windows 防火墙放行 8000 端口;
  ⚠️ 校园网/公司网的 DHCP 会让电脑 IP 变化,登录页「服务器设置」里填的旧地址会失效 —— 连不上时先用手机浏览器访问该地址确认,再回 App 改;
  若校园网开了客户端隔离(同网段也不能互访),改用手机热点让电脑连,或用模拟器的 `10.0.2.2`(**只对安卓模拟器有效**,鸿蒙端不行);
- 网络对战所有设备连同一台服务器即可;蓝牙对战无需任何服务器与 Wi-Fi。

## 🚀 服务端:一条 bat 启动(推荐)

```powershell
scripts\start-server-smtp.bat          # 默认 8000 端口
scripts\start-server-smtp.bat 8123     # 需要时指定端口
```

**双击即可,不用记任何命令、不用手动 set 环境变量。** 这个脚本会依次:

| 步骤 | 做什么 | 为什么 |
|---|---|---|
| 1 | 检查端口是否被占用,占用就问你要不要清掉 | 避免"改了代码,实际连的还是旧进程" |
| 2 | 读 `server/smtp.env`(UTF-8 显式解码、空值跳过) | 邮箱授权码不进版本库,配置与代码分离 |
| 3 | 打印**脱敏**配置 + **本机所有局域网地址** | 客户端设置页照抄那个 `http://<IP>:8000/` |
| 4 | 在 `0.0.0.0:<端口>` 起 FastAPI | 安卓端、鸿蒙端、模拟器、真机都能连 |

启动输出示例:

```
========================================
  PixelGame Server (SMTP enabled)
  Port: 8000
========================================

[config] loaded ...\server\smtp.env (7 keys)
[config] SMTP_HOST = smtp.qq.com
[config] SMTP_PORT = 465  (SSL = 1)
[config] SMTP_PASS = z***************
[config] ECHO_CODE = 1  (demo mode: the app shows the code itself)
[ok] SMTP looks configured - verification emails will be sent for real

[client] fill one of these into the app (Settings / Server):
         http://192.168.43.50:8000/
```

## 📚 文档导航

| 文档 | 用途 |
|---|---|
| `README.md`(本文) | 项目总览、怎么跑、目录结构、常见问题 |
| **`作品说明书-最终版.html`** | **课程提交用报告(推荐)**:用 Word 打开后"另存为 .docx"即可——样式已按课程要求设好(正文宋体小四、行距 20 磅、标题黑体、图注居中),插图放 `docs/images/` 自动显示 |
| **`作品说明书3-最终版.md`** | 同一份报告的 markdown 版(便于直接阅读/复制) |
| `作品说明书.md` / `作品说明书2.md` | 两份更详细的设计与实现底稿(含大量设计细节与代码片段,可作为答疑材料) |
| `鸿蒙版移植计划.md` | 鸿蒙端移植方案 + 真机问题修复记录(第 9 节,按轮次记录) |
| `tools/README.md` | 工具链说明(关卡生成 / 双端同步 / 一致性审计) |

## 🛠️ 构建与测试

| 依赖 | 版本 |
|------|------|
| JDK | 21+ |
| Android SDK | compileSdk 36 / minSdk 24 |
| Android Studio | 最新稳定版 |
| Python | 3.10+ |

```bash
./gradlew testDebugUnitTest    # JVM 单元测试(逻辑/映射/对战线/蓝牙仲裁)
./gradlew assembleDebug        # 构建 Debug APK
```

单测覆盖:全部关卡唯一解与答案一致、提示逐步推导收敛到答案、DTO↔JSON 契约、额度每日规则、邮箱登录规则(格式校验/验证码格式/重发倒计时)、对战帧协议(含重赛/NTP 校时)、会话状态机、蓝牙掩码校验与结算排序、**页面导航(下一关/关卡表不断链)** —— 共 **59 项**(8 个测试类);服务端冒烟覆盖邮箱验证码全链路(发码/限流/过期/错码上限/单次使用/老用户复用账号)、昵称持久化、房间发车/结算/重赛/断线重进/房主宽限回收(共 11 组冒烟断点)。`pip install -r requirements.txt` 后 `cd server && python smoke_test.py` 即可验证(脚本自建临时库、跑完自清理,可重复运行)。

内置关图案工具链(`tools/`):改 `Levels.kt`(同步 `gen_clues.py` 图案副本)→ `python tools/verify_clues.py` 定位失效关 → `python tools/gen_clues.py <关名>` 重生成提示集并贴回 `SparseClues.kt` → `./gradlew testDebugUnitTest` 终审。另有两个**只读审计**脚本:`compare_platforms.py`(双端符号级比对)、`audit_buttons.py`(找"按了没反应"的按钮写法)—— 详见 `tools/README.md`。

## 🧩 鸿蒙版(HarmonyOS · ArkTS)

工程位于 `../DEVxiangs`(DevEco Studio 工程),**ArkTS 源码托管在本仓库 `harmony/`**,
两处用一条命令同步 —— 这样"鸿蒙版改了什么"在 git 里一目了然:

```powershell
python tools/gen_harmony_levels.py   # 从 Levels.kt/SparseClues.kt 生成关卡数据(自带唯一解校验)
python tools/sync_harmony.py         # harmony/ → DEVxiangs(只增改、不删除)
```

| 项 | 说明 |
|---|---|
| SDK | HarmonyOS 6.1.1(API 24),纯 ArkTS / NEXT |
| 目录 | 与 Android **同名镜像**:`model/ data/ api/ auth/ battle/ service/ ui/` |
| 单测 | `@ohos/hypium`;不被页面引用的库靠单测 import 强制编译验证 |
| 一致性 | 帧协议、DTO、preferences 键名、关卡数据、配色与安卓端一致(见 `鸿蒙版移植计划.md` 7.1) |
| 网络对战 | 与安卓端**同房可玩**(服务端零改动);**蓝牙对战不实现**(平台能力差异) |
| 构建 | 见 `鸿蒙版移植计划.md` 的命令行构建小节(也可直接在 DevEco 里 Build) |

### 鸿蒙工程一键脚本(DEVxiangs\scripts\,双击即用)

源码在 `harmony/scripts/`,由 `python tools/sync_harmony.py` 同步进 DevEco 工程:

| 脚本 | 作用 |
|---|---|
| `check-env.bat` | 环境自检(SDK / node / hvigorw / hdc / 设备 / 本机 IP) |
| `build-hap.bat` | 清理 + 编译出 HAP(`--fast` = 只增量编译) |
| `run-hap.bat` | 安装到设备并启动(`--launch` = 只启动) |
| `log-hap.bat` | 看设备日志(等价于 logcat) |
| `start-server.bat` | 转调 `scripts\start-server-smtp.bat` 启动服务端 |

> 脚本默认加 `--no-daemon`(hvigor 守护进程崩过一次就很难恢复),
> 且用 `timeout` 代替 `pause`(非交互启动时 `pause` 会永久挂住)。

### 棋盘视口与性能(鸿蒙端,与安卓端同口径)

- **1× = 整盘适配**:格子边长 = `min(视口宽/列数, 视口高/行数)`,整盘恒定完整可见、**永不越界**;
- `zoom ∈ [1, 12]`:1× 是全貌,放大只看细节;**放大后**右上角才出现「复位」(不放大不显示,不挡格子);
- **单指 / 鼠标涂色走 `onTouch` 原始事件**(不过手势识别器):落下即落笔、拖动连涂、抬起提交,
  与安卓端的指针处理同构 —— 这样**预览器里鼠标点击也能涂**;
- **双指**捏合缩放 / 平移(第二指落下会取消当前笔划);**快速双击** = 1× ⇄ 2×;
- **不放常驻按钮**;顶栏副标题显示 `难度 ★ · 行×列 · 模式`;
- **性能**:纸面 + 网格 + 数字预渲染成 `ImageBitmap` 缓存,每帧一次 `drawImage`;
  网格用 O(N) 线条代替逐格 `strokeRect`,空白格跳过不画 —— 这是"预览器里涂得慢"的修法。

### 双端一致性审计

`python tools/compare_platforms.py` 按文件配对抽取两端的函数/常量/属性名做差集,
快速定位"鸿蒙端少实现了什么"(机械筛出候选,再由人确认)。

第五轮审计(见 `鸿蒙版移植计划.md` 9.4.3)共发现 **50+ 处差异**,已按「对战可用性 / 单人体验 /
页面与观感 / 通用控件」四批全部补齐,其中包含 5 个严重隐患:

| 隐患 | 之前的表现 |
|---|---|
| 未处理系统返回键 | 按返回**直接退出 App** |
| 在线关成绩不上传 | 排行榜单人榜永远空、`my-puzzles` 不增长 |
| 401 不登出 | 停在"假登录":界面已登录、在线功能静默失败 |
| 涂黑笔擦不掉已涂黑格 | 涂错只能撤销/重置 |
| 首登不弹昵称设置 | 与安卓引导流程不一致 |

**有意保留的差异**:蓝牙对战(平台能力)、通关彩纸与庆祝视频(无素材)、
宽屏/平板侧栏布局(鸿蒙端仍为单列,建议单独立项)。


### ⚠ 鸿蒙端连服务器:默认地址要改

鸿蒙**模拟器和真机都不能用** `10.0.2.2`(那是 Android 模拟器的宿主机别名)或 `localhost`
(指模拟器自己),**必须填开发电脑在局域网里的真实 IPv4**:

```powershell
ipconfig                     # 看 WLAN / 以太网的 IPv4,例如 192.168.43.50
scripts\start-server-smtp.bat # 服务端:必须用这个脚本启动(会加载 smtp.env,读信配置才生效)
```

然后在 App 的「服务器设置」里填 `http://<电脑IPv4>:8000/` → 点 **「测试连接」**
(请求服务端 `/` 做自检,直接告诉你通不通、SMTP 有没有配好)→ 通过后「保存」→ 再点「获取验证码」。

> 演示省事:`server/smtp.env` 里 `XIANGSU_ECHO_CODE=1` 时,验证码会**直接在 App 里显示**
> (演示模式),不必收邮件,答辩现场不怕邮件进垃圾箱;对外部署请改回 `0`。
> 详细排查步骤见 `鸿蒙版移植计划.md` 第 9 节。


## 🚧 后续规划

- [x] 鸿蒙(HarmonyOS)端:逻辑与界面层重写,服务端与协议零改动 —— **已完成**
- [ ] 微信小程序客户端(加分项):同一 HTTP + WS API

## 🎨 素材来源

- **背景音乐**:`Event_BGM_MiniGamePar`,取自[爱给网 aigei.com](https://www.aigei.com/)免费素材区
  (原文件名 `塞尔达旷野之息——Event_BGM_MiniGamePar_爱给网_aigei_com.mp3`,
  已重命名为 `app/src/main/res/raw/bgm.mp3`)。**仅用于课程/竞赛演示**,
  如需公开发布请替换为自有版权或明确可商用的音频。
- **音效**:`tools/gen_audio.py` 程序化合成(点击/通关/错误),无第三方素材。
- **图标/配色**:项目自绘(矢量 drawable + Compose 主题色),无第三方素材。

## 📄 许可

项目仅供学习交流使用,暂无开源许可。
