# 行尾与源码格式

仓库配置决定文本行尾与源码格式，不依赖个人 Git 或编辑器默认设置。普通文本统一使用 UTF-8、LF 和文件末尾换行；Windows `.bat` / `.cmd` 工作副本使用 CRLF；二进制不转换。采用体素引擎的仓库级约定，按本项目的 Gradle 批处理和 Slang/Java 源码调整范围。

## 配置与工具

| 配置 | 约束 |
| --- | --- |
| [.gitattributes](../../.gitattributes) | 自动识别文本并规范为 LF；批处理检出为 CRLF；已知二进制显式 `-text` |
| [.editorconfig](../../.editorconfig) | 编码、行尾、末尾换行与源码四空格缩进，排除二进制 |
| [rustfmt.toml](../../rustfmt.toml) | Rust 使用 rustfmt，Unix 行尾、四空格、100 列 |
| [.clang-format](../../.clang-format) | Java 与 Slang 使用 clang-format，四空格、100 列；保留 import 顺序和注释内容 |
| [scripts/format.ps1](../../scripts/format.ps1) | 格式化或只读检查全部受维护的 Rust、Java、Slang 源码 |

Java 按 `.java` 语言模式处理，Slang 按 C++ 风格词法格式化。当前使用 clang-format 22.1.8；Rust 使用所选工具链提供的 rustfmt。100 列是工具的换行目标，不要求手工拆分字符串、注释或改变表达式语义。没有必须遵循的额外手工风格，统一交给配置与工具；升级格式工具时检查产生的差异。

脚本不处理 `artifacts`、游戏运行目录、生成的 Java class 或构建产物，也不启动游戏、下载工具、安装 hook 或暂存文件。格式化不能代替 javac、Rust 与生产 Slang 的编译校验；Slang 的属性、指针和射线查询扩展以实际 slangc 编译为准，不能为迎合格式器修改 shader 语义。

## 日常使用

在仓库根目录的 PowerShell 中执行；需要 `cargo`、rustfmt 和 `clang-format` 已在 PATH：

```powershell
# 应用格式
.\scripts\format.ps1

# 只读检查，不修改源码
.\scripts\format.ps1 -Check

git ls-files --eol
git check-attr text eol -- gradlew.bat crates/prime-vulkan/shaders/path_trace.slang
git diff --check
git diff --numstat --ignore-cr-at-eol
git diff --cached --numstat --ignore-cr-at-eol
```

纯 CRLF/LF 转换在 `--ignore-cr-at-eol` 下应消失；新增文件末尾换行和真正的格式调整仍会显示差异，需要分别核对。不要默认使用范围更大的 `-w` 隐藏其他修改。新增二进制类型应补充 attributes 与 EditorConfig 排除，不对二进制执行文本重写。

## 首次规范化与提交

添加 attributes 不会自动重写现有暂存区。提交规范化时，先确认普通修改、新增和删除的提交范围，再按需运行 `git add --renormalize .`，检查暂存差异。该命令只重新清理受跟踪文件，不代替新增/删除处理，也不能用来顺带暂存未确认的工作。

不要通过强制检出、清空或重置工作区刷新行尾；格式化不包含自动暂存、提交或修改个人 Git 配置。attributes 中的批处理 CRLF 只影响工作副本，Git 索引仍规范为 LF。

参考：[Git attributes 的行尾规则](https://git-scm.com/docs/gitattributes#_end_of_line_conversion)、[Git diff 的行尾比较选项](https://git-scm.com/docs/git-diff#Documentation/git-diff.txt---ignore-cr-at-eol)。
