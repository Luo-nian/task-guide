# debug · v5.15.29

> 本版真 bug 与根因记录。索引见 `BUG-INDEX.md`。

## C-029 · 手机端 `Emblem.kt` 三个 Kotlin 编译坑（一轮踩一个）—— 已解决

新写的徽章渲染器连续 3 次 BUILD FAILED，每次只报一个错，攒起来是三条通用经验：

| # | 报错 | 根因 | 修法 |
| --- | --- | --- | --- |
| 1 | `Variable expected` / `Unresolved reference: scaleX/scaleY/rotationZ/translationY` | 用了 **lambda 版** `graphicsLayer { this.alpha = alpha; scaleX = scl; ... }` —— lambda 的 receiver 属性（`alpha`）与**同名局部变量**打架 | 改成**参数式**：`graphicsLayer(scaleX = scl, scaleY = scl, alpha = alpha, rotationZ = rot, translationY = dy)` |
| 2 | `Unresolved reference: width / height / minDimension` | 函数参数名 `size: Dp` **遮蔽**了 `DrawScope.size`（Size）→ `size.width` 被解析成 `Dp.width` | 在 Canvas lambda 里写 **`this.size`**（或先把参数改名） |
| 3 | `Unresolved reference: size`（指向 `.size(size)` 的 `.size`） | **忘了 import** —— `Modifier.size(Dp)` 是 `androidx.compose.foundation.layout.size` 扩展函数，只 import `Modifier` 不够 | 补 `import androidx.compose.foundation.layout.size` |

> **通用教训**：
> ① Compose 里凡是"lambda 接收者属性"和"局部变量"同名，优先用**参数式 API**（`graphicsLayer(alpha=…)` 这类），
>    别用 lambda 版；② **不要用 `size` 当参数名**（`DrawScope.size` 会被遮蔽，症状是莫名 Unresolved）；
> ③ Kotlin 的 `Modifier.xxx()` 几乎都是**扩展函数**，"Unresolved reference: xxx" 先查 import，
>    而不是先怀疑写法。

---

## C-028 ⭐⭐「删了 HTML 元素、JS 顶层还在绑事件」→ **整页停摆** —— 已解决

**症状**：头像下架 + 徽章 + 昵称那一版编译部署后，界面**一动不动** ——
问候语永远「下午好，boss」、积分永远「409 分」（这两个都是 `index.html` 里的**静态死值**），
点任何按钮都没反应。

**根因**：`#profileAvatarFile` 被我随头像 UI 一起从 HTML 删掉了，但 `app3.js` **顶层**还留着

```js
document.getElementById('profileAvatarFile').addEventListener('change', ...)   // ← 元素为 null
```

→ 抛 `TypeError` → **顶层代码从此中断** → 它后面那句

```js
if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', startApp);
```

**根本没被注册** → `startApp()` 从未被调用。

**排查过程（这次的关键，值得复用）**：

1. 先验数据层：手动 `render()` → 数据正常（1555 分 / 星辰霸主）→ 排除"后端没数据"
2. 查 `typeof startApp` → `'function'` —— **⚠️ 这是 hoist 假象，不能当依据**（函数声明会被提升，
   即使脚本在中途崩掉它照样是 function）
3. **手动执行 `startApp()`** → 报 `Cannot access '_autoScanWatchTimer' before initialization`
   → 这是 **TDZ（暂时性死区）错误**，意味着 `let _autoScanWatchTimer` 那条**声明语句根本没执行到**
   → 反推：**它之前的某条顶层语句抛了异常，把顶层执行链砍断了**
4. `grep -n "profileAvatarFile\|avatarPicker\|avatarGrid\|profileAvatar" app3.js`
   → 定位 2607 行的裸绑定 ✅

**修法**：加空保护

```js
const _phFileEl = document.getElementById('profileAvatarFile');
if (_phFileEl) _phFileEl.addEventListener('change', e => { ... });
```

**顺带做的系统性检查**（脚本已存进 `design/存档-自定义头像-UI代码-v1.md` 第八节）：
「JS 里 `getElementById` 引用的 id」**差集**「HTML 里真实存在的 id」→ 本次列出 22 个缺失 id，
再逐个确认哪些是"动态创建"、哪些是"有 `&&` 保护"、哪些是"裸访问"。本次只有 1 个是**顶层裸绑定**。

> **教训**：
> 1. 这是铁律 #2（顶层事件绑定必须空保护）的**重犯**，而且后果更严重 ——
>    不是"某个按钮点不动"，而是**整个页面不启动、所有交互全废**；
> 2. **`typeof fn === 'function'` 不能证明脚本跑到底了**；判断"顶层是否中断"最快的判据是
>    **手动调用它、看有没有 TDZ 错误**（`Cannot access 'x' before initialization`）；
> 3. **删 HTML 元素后必须跑一次"引用存在性检查"**（尤其 `addEventListener` / `.style` / `.innerHTML`
>    这类紧跟在 `getElementById(...)` 后面的写法）。

---

## C-027 ⭐⭐ CSS 注释收尾写成 `-->`，一次吞掉四条规则 —— 已解决

**症状**：超时弹窗改完样式、也编译部署了，但截图一看：**标题栏挤在左边、× 没靠右、"即将超时的任务"小标签完全没样式**。
不是审美问题，是规则根本没生效。

**根因**：我在 CSS 里写块注释时，**收尾写成了 HTML 的 `-->`**，而不是 CSS 的 `*/`：

```css
/* v5.15.29 E1b（boss：「逾期提醒窗口还是不能一眼看到重点」）——
   ...最终层级：任务名 28px/800 墨黑 ＞ 倒计时胶囊 13px 朱砂 ＞ 小标签 11px 淡 -->   ← 错！
.em-bar { display: flex; ... }        ← 被吞
.em-alert { ... }                     ← 被吞
.em-title { flex: 1; ... }            ← 被吞
.em-body { padding: ... }             ← 被吞
/* 任务名前的小标签 */                    ← 这里才出现真正的 */
```

于是从那条注释起，**`.em-bar` / `.em-alert` / `.em-title` / `.em-body` 四条规则全部变成注释内容**，
只有后面带 `*/` 的规则才是活的。

**修复**：`-->` → `*/`，并全文件 `grep -- '-->'` 复查（已为空）。

**教训**：
- ⭐ **CSS 注释只能 `*/` 收尾**；`-->` 是 HTML 的写法。语言混用时最容易犯
- ⭐ 症状识别：**同一个块里"有的规则生效、有的没生效"，且失效的正好是连在一片"、"之前的** → 优先怀疑注释被吞
- ⭐ **改完样式必须看真实渲染**（截图/DOM 计算样式），"编译成功"和"规则生效"是两件事
- 补丁脚本里可以顺手加一条自检：写完全文件 `grep -c -- '-->'` 必须为 0

---

## C-025 ⭐⭐ 预览里的 SMIL 动画会被渲染器丢掉 —— 已规避

用 `<animate>` / `<animateTransform>` 做的星空动效，在 boss 那边看到的是**静态图**（等于"没做"）。
**一律改用 CSS 关键帧**。另：CSS 里旋转/缩放 SVG 元素必须写 `transform-box: fill-box` + `transform-origin: center`。

---

## P1 电脑端「追踪中」两套渲染 —— 已解决

侧边栏「追踪」页标题写「追踪中」且**没有 `.cg-body`**（无浅金底/描边/下圆角），今日待办置顶组写「正在追踪」且有框 → 看着"不统一"。
修法：标题统一「正在追踪」+ 补 `.cg-body` + 不可点组 `cursor:default`。CDP 实测通过。

