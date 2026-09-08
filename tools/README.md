# 图案与稀疏提示生成工具

这些脚本用于**重新生成 / 校验**游戏内置关卡的像素图案与稀疏提示（保证唯一解），
是 `Levels.kt` 与 `SparseClues.kt` 的配套开发工具。

> 用法：在项目根目录执行 `python tools/<脚本>.py`（需 Python 3）。

| 脚本 | 作用 |
|------|------|
| `render_patterns.py` | 把 `Levels.kt` 里的图案渲染成终端字符画，肉眼核对造型 |
| `final_patterns.py` | 当前最终版图案的渲染（`__main__` 才渲染，可被其它脚本导入） |
| `gen_clues.py` | 从图案答案出发，贪心去提示 + 唯一解校验，生成稀疏提示的 Kotlin 代码 |
| `verify_clues.py` | 解析 `SparseClues.kt`，逐个关卡校验提示集是否仍唯一解 |
| `update_clues.py` | 把 `gen_clues.py` 输出的提示集安全写回 `SparseClues.kt` |

## 修改图案后的标准流程

1. 改 `Levels.kt` 里的图案（小图用 ASCII `art(...)`，大图用绘图工具函数）。
2. 运行 `python tools/verify_clues.py`：
   - 若某关 `OK (unique, keep)` → 该关提示无需变；
   - 若某关 `NOT-UNIQUE (regenerate)` → 对该关运行 `python tools/gen_clues.py <name>`，
     把输出的 `val <name>: Set<Pair<Int, Int>>` 贴给 `update_clues.py` 写回。
3. 跑 `./gradlew testDebugUnitTest`，`CoreLogicTest` 会最终把关"每个内置关卡只有唯一解"。
