# 像素填空 (Fill-a-Pix) · 康思谜题联网版

> 逻辑与像素的碰撞 —— 基于数字线索推理的康思像素画解谜游戏
> 支持 **Android**(已完成)与 **鸿蒙**(规划中,复用同一服务端与协议,仅重写界面层)

Fill-a-Pix(像素填空)是康思谜题(Conceptis Puzzles)旗下的经典逻辑谜题:棋盘上的每个数字提示告诉你以它为中心的 3×3 区域内需要涂黑的格子数量,玩家通过逻辑推理确定每个格子的状态,最终拼出一幅完整的像素画。

**Kotlin + Jetpack Compose**(Android) + **Python FastAPI + SQLite**(服务端)。逻辑层纯 Kotlin 无 Android 依赖,可在 JVM 直接单测;关卡由**服务端即时生成并校验唯一解**(算法与 `tools/` 工具链共用同一 Python 引擎)。

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
├── app/src/main/java/com/example/xiangsugame/
│   ├── MainActivity.kt        # 入口 + 栈式路由 + AppGraph 依赖容器
│   ├── api/                   # Retrofit + OkHttp + kotlinx-serialization
│   │   ├── GameApi.kt         # REST 接口声明(登录/题库/每日/排行榜/建房)
│   │   ├── ApiClient.kt       # 统一 baseUrl、Bearer 注入、错误解析
│   │   └── dto/Dtos.kt        # Level JSON 契约 + Level↔DTO 双向映射
│   ├── auth/                  # 邮箱验证码登录会话
│   │   ├── AuthManager.kt     # 发码/登录/会话持久化(客户端不生成任何 code)
│   │   └── LoginRules.kt      # 邮箱校验/验证码格式/倒计时(纯逻辑,可单测)
│   ├── data/                  # 账号进度(按 userId 分片)/ 在线关卡缓存 / 题库仓库
│   ├── battle/                # 对战线:帧协议 + 传输抽象
│   │   ├── BattleProtocol.kt  # 帧协议(网络/蓝牙同构)
│   │   ├── BattleSession.kt   # 与传输无关的竞速状态机
│   │   ├── NetworkBattleTransport.kt   # WebSocket 房(服务器中转)
│   │   └── BluetoothBattleTransport.kt # RFCOMM 直连 + 双端对称仲裁
│   ├── model/                 # 核心逻辑(纯 Kotlin):Level/GameBoard/Validator/
│   │                          #   PuzzleGenerator/PuzzleSolver/Levels/SparseClues
│   └── ui/                    # Compose:登录/首页/题库/排行榜/对战大厅/游戏页…
├── server/                    # Python FastAPI + SQLite 服务端
│   ├── main.py                # 入口(lifespan 建表 + 题库预热)
│   ├── auth.py puzzles.py leaderboard.py rooms.py db.py content.py
│   ├── mailer.py              # 验证码邮件(SMTP 真发 + 控制台兜底)
│   ├── smtp.env.example       # 发信配置模板(真实配置 smtp.env 已被 gitignore)
│   ├── puzzle_engine.py       # 共享谜题引擎(求解器/唯一解/稀疏提示生成)
│   ├── puzzle_art.py          # 随机像素画生成器(可种子复现)
│   ├── generator.py           # 难度/尺寸策略产题 + 唯一解双保险
│   ├── seed_puzzles.py        # 题库预热 CLI
│   └── smoke_test.py          # 纯 assert 冒烟测试
├── scripts/                   # 启动脚本(双击即用)
│   ├── start-server-smtp.bat  # 启动服务端(读 server/smtp.env 发信配置)
│   └── start_server_smtp.ps1  #   其实现:读配置 + 授权码脱敏回显 + 起服务
└── tools/                     # 内置关工具链:gen_clues / verify_clues / gen_audio
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
3) 双击 scripts\start-server-smtp.bat
```

首次运行前先装依赖:`cd server && pip install -r requirements.txt`。

启动时会打印生效配置(授权码自动脱敏,只显示首字符),便于确认到底有没有配上:

```
[config] loaded ...\server\smtp.env
[config] SMTP_HOST = smtp.qq.com
[config] SMTP_PASS = z***************
[ok] SMTP looks configured - verification emails will be sent for real
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

- 模拟器:默认地址 `http://10.0.2.2:8000/` 即宿主机;- 真机:与电脑同一 Wi-Fi,App「设置」把服务器地址改成 `http://<电脑局域网IP>:8000/`,Windows 防火墙放行 8000 端口;
  ⚠️ 校园网/公司网的 DHCP 会让电脑 IP 变化,登录页「服务器设置」里填的旧地址会失效 —— 连不上时先用手机浏览器访问该地址确认,再回 App 改;
  若校园网开了客户端隔离(同网段也不能互访),改用手机热点让电脑连,或用模拟器的 `10.0.2.2`;
- 网络对战所有设备连同一台服务器即可;蓝牙对战无需任何服务器与 Wi-Fi。

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

单测覆盖:全部关卡唯一解与答案一致、提示逐步推导收敛到答案、DTO↔JSON 契约、额度每日规则、邮箱登录规则(格式校验/验证码格式/重发倒计时)、对战帧协议(含重赛/NTP 校时)、会话状态机、蓝牙掩码校验与结算排序(50+ 用例);服务端冒烟覆盖邮箱验证码全链路(发码/限流/过期/错码上限/单次使用/老用户复用账号)、昵称持久化、房间发车/结算/重赛/断线重进/房主宽限回收。`pip install -r requirements.txt` 后 `cd server && python smoke_test.py` 即可验证。

内置关图案工具链(`tools/`,只保留 3 个常驻脚本):改 `Levels.kt`(同步 `gen_clues.py` 图案副本)→ `python tools/verify_clues.py` 定位失效关 → `python tools/gen_clues.py <关名>` 重生成提示集并贴回 `SparseClues.kt` → `./gradlew testDebugUnitTest` 终审。

## 🚧 后续规划

- [ ] 鸿蒙(DevEco Studio)端:逻辑移植/界面重写,服务端与协议零改动
- [ ] 微信小程序客户端(加分项):同一 HTTP + WS API

## 📄 许可

项目仅供学习交流使用,暂无开源许可。
