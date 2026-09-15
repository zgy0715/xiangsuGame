# 图案与稀疏提示生成工具

这些脚本用于**重新生成 / 校验**游戏内置关卡的像素图案与稀疏提示（保证唯一解），
是 `Levels.kt` 与 `SparseClues.kt` 的配套开发工具。

> 用法：在项目根目录执行 `python tools/<脚本>.py`（需 Python 3）。

| 脚本 | 作用 |
|------|------|
| `gen_clues.py [关名]` | 图案答案 → 贪心去提示 + 唯一解校验 → 输出稀疏提示的 Kotlin 代码段 |
| `verify_clues.py` | 解析 `SparseClues.kt`，逐个关卡校验提示集是否仍唯一解（回归闸门） |
| `gen_audio.py` | 合成点击 / 通关 / 错误音效到 `res/raw/`（需 numpy + scipy） |

求解器与绘图原语统一来自 `server/puzzle_engine.py`（与线上产题共用同一实现），
`gen_clues.py` 只保留“图案定义 + Kotlin 字面量输出”，不各自复制一份引擎。

## 修改图案后的标准流程

1. 改 `Levels.kt` 里的图案（小图用 ASCII `art(...)`，大图用绘图工具函数）。
2. 同步 `tools/gen_clues.py` 中的同名图案副本（两处必须一致）。
3. 运行 `python tools/verify_clues.py`：
   - 若某关 `OK (unique, keep)` → 该关提示无需变；
   - 若某关 `NOT-UNIQUE (regenerate)` → 对该关运行 `python tools/gen_clues.py <name>`，
     把输出的 `val <name>: Set<Pair<Int, Int>>` 整段贴回 `SparseClues.kt`。
4. 跑 `./gradlew testDebugUnitTest`，`CoreLogicTest` 会最终把关“每个内置关卡只有唯一解”。
