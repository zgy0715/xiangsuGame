# 工具链说明

本目录分三类脚本:**关卡数据生成/校验**、**双端同步**、**双端一致性审计**。
均为开发期工具,不参与 App 运行时。

> 用法:在项目根目录执行 `python tools/<脚本>.py`(需 Python 3)。

## 一、关卡数据生成与校验

| 脚本 | 作用 |
|------|------|
| `gen_clues.py [关名]` | 图案答案 → 贪心去提示 + 唯一解校验 → 输出稀疏提示的 Kotlin 代码段 |
| `verify_clues.py` | 解析 `SparseClues.kt`,逐个关卡校验提示集是否仍唯一解(回归闸门) |
| `gen_audio.py` | 合成点击 / 通关 / 错误音效到 `res/raw/`(需 numpy + scipy) |

求解器与绘图原语统一来自 `server/puzzle_engine.py`(与线上产题共用同一实现),
`gen_clues.py` 只保留"图案定义 + Kotlin 字面量输出",不各自复制一份引擎。

### 修改图案后的标准流程

1. 改 `Levels.kt` 里的图案(小图用 ASCII `art(...)`,大图用绘图工具函数)。
2. 同步 `tools/gen_clues.py` 中的同名图案副本(两处必须一致)。
3. 运行 `python tools/verify_clues.py`:
   - 若某关 `OK (unique, keep)` → 该关提示无需变;
   - 若某关 `NOT-UNIQUE (regenerate)` → 对该关运行 `python tools/gen_clues.py <name>`,
     把输出的 `val <name>: Set<Pair<Int, Int>>` 整段贴回 `SparseClues.kt`。
4. 跑 `./gradlew testDebugUnitTest`,`CoreLogicTest` 会最终把关"每个内置关卡只有唯一解"。

## 二、鸿蒙端数据生成与同步

鸿蒙端 ArkTS 源码托管在本仓库 `harmony/` 下,DevEco 工程(`DEVxiangs`)只是构建工作区:

| 脚本 | 作用 |
|------|------|
| `gen_harmony_levels.py` | 从 `Levels.kt`/`SparseClues.kt` 生成 `harmony/ets/model/GeneratedLevels.ets`;生成时逐关自检"图案与 Levels.kt 逐行一致 + 提示集唯一解 = 答案" |
| `sync_harmony.py [--check]` | `harmony/{ets,resources,scripts,test}` → DEVxiangs 对应目录;并**自动修正编码**:`.ps1` 补 UTF-8 BOM、`.bat` 去 BOM(踩过的坑,见下) |
| `make_harmony_profile.py` | 生成鸿蒙签名用的 Provision Profile(换包名 / 刷有效期 / 内嵌证书链) |

```powershell
python tools/gen_harmony_levels.py   # 改完 Levels.kt 后重跑
python tools/sync_harmony.py         # 同步进 DEVxiangs
python tools/sync_harmony.py --check # 只报告差异
powershell -File harmony\scripts\sign-hap.ps1 -ProjectRoot <DEVxiangs 路径>   # 编译后签名
```

> **编码坑(Win PowerShell 5.1)**:`.ps1` 必须 UTF-8 **with BOM**(无 BOM 会按 GBK 解析,
> 中文与破折号直接导致语法错误);`.bat` 必须**无 BOM**(cmd 会把 BOM 读进第一条命令)。
> `sync_harmony.py` 会自动处理,免得每次手改都要记得。

## 三、双端一致性审计

| 脚本 | 作用 |
|------|------|
| `compare_platforms.py` | **符号级机械比对**:按文件配对抽取两端的函数/常量/属性名做差集,快速定位"鸿蒙端少实现了什么"(机械筛候选,人工确认) |
| `audit_buttons.py` | **按钮可用性审计**:扫出会"按了没反应"的写法 —— `enabled(false)` 静默禁用、Button 没有 `onClick`、非 Button 挂 onClick、一排按钮过多或热区 <32vp |

```powershell
python tools/compare_platforms.py   # 输出疑似缺失清单
python tools/audit_buttons.py       # 输出按钮问题清单(E1~E4)
```

两者都是**只读审计**,不改代码;结论需要人工确认后再改。
