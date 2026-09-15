# 存档：自定义头像 UI（v5.15.29 L3 起下架 · 不实装）

> **boss 原话**：「把自定义头像暂时存档 不实装在app上 手机端也是 原本头像的位置放等级徽章」
> **下架时间**：2026-09-15　**下架前最后 commit**：`bf8b4a0`
> 本文件保存下架当时的 UI 代码全文，**随时可原样贴回**。

---

## 一、下架了什么（双端）

| 位置 | 下架内容 | 现位置 |
| --- | --- | --- |
| 手机 「我的」页 | 大头像（点它选图）+ 「上传头像」按钮 + 8 个内置 emoji 头像网格 + 相册选图 launcher + 圆形裁剪弹窗 `AvatarCropDialog` + `rememberAvatarBitmap` | **等级徽章卡**（深色圆盘 + 金边 + 徽章本体 + 等级名/分数） |
| 电脑端 个人信息页 | 头像本体 `#profileAvatar` + 「点击更换」`#profileAvatarHint` + 文件选择 `#profileAvatarFile` + 内置头像网格 `#avatarPicker` / `#avatarGrid` | **等级徽章**（`#profileEmblem` + `Lv.N` 角标） |
| 电脑端 身份卡 | 环内头像（`#dashGreetGfx` 里的图/emoji） | **等级徽章**（同一环内，`#dashGreetEmblem`） |

## 二、**没有**下架的部分（数据层完整保留 · 通道是活的）

- 手机端 prefs：`avatar_emoji`（本地缓存）
- 同步设置 key：`avatar_img`（base64 自定义图）/ `avatar_emoji` / `avatar_idx`
- 电脑端 settings：`avatar_img` / `avatar_idx` / `avatar_emoji`
- 电脑端 `hydrateUserProfile()` 的头像拉取、`onRemoteSyncApplied()` 里"最新意图"时间戳逻辑（`sync_ts_avatar_img` / `sync_ts_avatar_emoji`）
- 电脑端 `AVATAR_GLYPHS` / `pickAvatarGlyph()` / `openCropper()` 裁剪器 / `svgIcon()` 等工具函数 —— **全部原样保留**（只是不再被调用）

> 结论：**恢复只需要把 UI 贴回来**，数据与同步逻辑不用重写。

## 三、恢复步骤

1. 取回原文（下架前的 commit `bf8b4a0`）：
   ```bash
   git show bf8b4a0:desktop/ui/index.html                              # 个人信息页头像三件套
   git show bf8b4a0:desktop/ui/app3.js                                 # renderProfileAvatar / renderUserAvatar / 网格渲染 / 事件绑定
   git show bf8b4a0:mobile/app/src/main/java/com/taskbar/app/ui/ProfileScreen.kt
   ```
2. **电脑端**：把「四、HTML 原文」贴回个人信息页 `profile-hero` 内；把「五、JS 原文」的两个函数恢复，并保留「六、事件绑定」。
3. **手机端**：把「七、手机端原文」整段贴回「我的」页（`ProfileScreen.kt`：问候语块之后、等级卡之前）。
4. ⚠️ 恢复后**必须**跑一遍第七节的"元素存在性检查"。

## 四、电脑端原文 · HTML（个人信息页）

```html
          <div class="ph-avatar-wrap">
            <div class="ph-avatar" id="profileAvatar" title="点换头像 / 长按选内置头像"><span class="ico-slot" data-icon="sparkle" data-icon-size="32" data-icon-sw="1.7"></span></div>
            <span class="ph-avatar-hint" id="profileAvatarHint">点击更换</span>
            <input type="file" id="profileAvatarFile" accept="image/*" hidden />
          </div>
          <!-- v5.14g：8 个 lucide 内置头像选择网格（默认折叠，点头像才展开） -->
          <div class="ph-avatar-picker" id="avatarPicker" style="display:none">
            <div class="ph-picker-hint">内置头像 · 点选即生效</div>
            <div class="ph-avatar-grid" id="avatarGrid"></div>
          </div>
```

## 五、电脑端原文 · JS（关键函数）

```js
// ===== renderProfileAvatar =====
function renderProfileAvatar() {
  const av = document.getElementById('profileAvatar');
  if (!av) return;
  if (settings.avatar_img) {
    // boss #20 fix：彻底改用 background-image + cover 方案，避免 img 在 flex 容器内对齐问题。
    // 容器 border-radius:50% + overflow:hidden + background-size:cover 永远完美裁圆，不会溢出。
    av.innerHTML = '';
    av.style.backgroundImage = "url(\"" + settings.avatar_img.replace(/"/g, '%22') + "\")";
    av.style.backgroundSize = 'cover';
    av.style.backgroundPosition = 'center';
    av.style.backgroundRepeat = 'no-repeat';
  } else {
    // v5.14h.14：头像 kind 路由（svgIcon for lucide / Text emoji for 动物 emoji）
    const g = pickAvatarGlyph();
    if (g.kind === 'emoji') {
      av.style.backgroundImage = '';
      av.innerHTML = '<span style="font-size:24px;line-height:1">' + g.ico + '</span>';
    } else {
      av.innerHTML = svgIcon(g.ico, 32, 1.7);
    }
  }
}

// ===== renderUserAvatar =====
function renderUserAvatar() {
  // 1) 顶栏（v5.14h.1 已简化为轻量文字卡，无头像元素，跳过）

  // 2) 下午好总览（v5.14h.1 完整勋章 v2：conic 环 + 头像 + Lv 角标 + 稀有度宝石）
  const greet = document.getElementById('dashGreetGfx');
  if (greet) {
    if (settings.avatar_img) {
      greet.innerHTML = '';
      greet.style.backgroundImage = "url(\"" + settings.avatar_img.replace(/"/g, '%22') + "\")";
      greet.style.backgroundSize = 'cover';
      greet.style.backgroundPosition = 'center';
      greet.style.backgroundRepeat = 'no-repeat';
    } else {
      greet.style.backgroundImage = '';
      const g = pickAvatarGlyph();
      if (g.kind === 'emoji') greet.innerHTML = '<span style="font-size:18px;line-height:1">' + g.ico + '</span>';
      else greet.innerHTML = svgIcon(g.ico, 24, 1.7);
    }
    // v5.14h.1：勋章进度环（距下级完成度）由 renderLevelBadge 写 ring-pct；Lv 角标 + 稀有度宝石也由它处理
  }
  // 3) profile modal
  renderProfileAvatar();
  // 4) 头像选择网格 active
  updateAvatarGridActive();
}

// ===== renderProfileEmblem =====
(未找到)

// ===== updateAvatarGridActive =====
function updateAvatarGridActive() {
  document.querySelectorAll('#avatarGrid .ph-av').forEach(c => {
    c.classList.toggle('active', parseInt(c.dataset.idx, 10) === avatarIdx);
  });
}
```

## 六、电脑端原文 · JS（事件绑定）

```js
// 上传自己的图片：点 hint 文字 → 选文件
const _fileTrigger = document.getElementById('profileAvatarHint');
if (_fileTrigger) {
  _fileTrigger.addEventListener('click', () => {
    const f = document.getElementById('profileAvatarFile');
    if (f) f.click();
  });
}
// v5.14d：长按头像（800ms）→ 显示/隐藏 8 头像选择器
let _avatarLongPressTimer = null;
const _profileAvatarEl = document.getElementById('profileAvatar');
if (_profileAvatarEl) {
  _profileAvatarEl.addEventListener('mousedown', () => {
    _avatarLongPressTimer = setTimeout(() => {
      const picker = document.getElementById('avatarPicker');
      if (picker) picker.style.display = picker.style.display === 'none' ? '' : 'none';
    }, 800);
  });
  ['mouseup','mouseleave','touchend','touchcancel'].forEach(ev => {
    _profileAvatarEl.addEventListener(ev, () => {
      if (_avatarLongPressTimer) { clearTimeout(_avatarLongPressTimer); _avatarLongPressTimer = null; }
    });
  });
}
// 上传自己的图片 → 打开裁剪器
// ⚠️ v5.15.29 L3：头像 UI 下架后这里**必须保留空保护**（详解见第七节）
const _phFileEl = document.getElementById('profileAvatarFile');
if (_phFileEl) _phFileEl.addEventListener('change', e => {
  const f = e.target.files && e.target.files[0];
  if (!f) return;
  openCropper(f);
  e.target.value = '';
});
```

## 七、手机端原文 · UI（ProfileScreen）

```kotlin
        // ===== v5.15：自定义头像 + 8 内置头像选择（本地 prefs avatar_emoji 持久化） =====
        // v5.15.7：电脑端裁剪上传的自定义头像（settings.avatar_img，跨端同步）优先级最高
        val ctx = LocalContext.current
        val prefs = remember { ctx.getSharedPreferences("taskguide_prefs", android.content.Context.MODE_PRIVATE) }
        var avatarEmoji by remember { mutableStateOf(prefs.getString("avatar_emoji", "") ?: "") }
        val syncedAvatar by vm.avatarImg.collectAsState()
        val syncedBmp = rememberAvatarBitmap(syncedAvatar)
        // v5.15.10：手机端也能自定义头像 —— 相册选图 → 只取中间圆形区域 → 256px PNG → 推电脑端
        val scope = rememberCoroutineScope()
        var uploading by remember { mutableStateOf(false) }
        // v5.15.12：选完图先进「裁剪界面」，由用户自己拖动/缩放决定圆形范围（boss：不要自动裁）
        // v5.15.14：改用 rememberSaveable 存 uri 字符串 —— 部分 ROM 的文件选择器返回时会重建 Activity，
        //   普通 remember 会丢状态 → 表现为"选完图什么都没发生"（实测踩到）
        var cropUriStr by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
        val pickImage = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) cropUriStr = uri.toString()
        }
        // 裁剪确认 → 生成圆形 PNG → 走设置同步通道推给电脑端
        cropUriStr?.let { us ->
            val u = android.net.Uri.parse(us)
            AvatarCropDialog(
                uri = u,
                onCancel = { cropUriStr = null },
                onConfirm = { dataUrl ->
                    cropUriStr = null
                    uploading = true
                    scope.launch {
                        vm.setSyncedSetting("avatar_img", dataUrl)
                        prefs.edit().putString("avatar_emoji", "").apply()
                        avatarEmoji = ""
                        uploading = false
                    }
                }
            )
        }
        val avatarPalette = listOf("#E8CB7F", "#D8B45A", "#C9A227", "#8CE0C8", "#5BA3D0", "#B49BE0", "#E07BD0", "#FF8A5B")
        val avatarEmojis = listOf("🦊", "🐯", "🦉", "🐺", "🐼", "🦁", "🐲", "🦅")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(TGColors.Card)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 大头像（自定义图 > 选中 emoji > 人形），点它 = 从相册选图
            Box(
                Modifier.size(68.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (avatarEmoji.isNotEmpty() || syncedBmp != null) Color(0xFF1A1206) else TGColors.GoldLight)
                    .border(2.dp, TGColors.Gold, androidx.compose.foundation.shape.CircleShape)
                    .clickable { pickImage.launch("image/*") },
                contentAlignment = Alignment.Center
            ) {
                if (syncedBmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = syncedBmp,
                        contentDescription = "头像",
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (avatarEmoji.isNotEmpty()) Text(avatarEmoji, fontSize = 32.sp)
                else TGIcon(R.drawable.ic_avatar, "头像", tint = TGColors.Ink, size = 34.dp)
            }
            Spacer(Modifier.width(10.dp))
            // 相册上传入口（自动裁中间圆形区域 + 同步电脑端）
            Column {
                Box(
                    Modifier.clip(RoundedCornerShape(999.dp))
                        .background(TGColors.Gold.copy(alpha = 0.18f))
                        .border(1.dp, TGColors.Gold, RoundedCornerShape(999.dp))
                        .clickable(enabled = !uploading) { pickImage.launch("image/*") }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        // v5.15.21 M4（boss：头像旁边的按键改为"上传头像"）
                        if (uploading) "处理中…" else "上传头像",
                        color = TGColors.GoldDeep, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                // v5.15.12：boss 要求去掉这行提示文案（改为裁剪界面里说明）

            }
            Spacer(Modifier.width(10.dp))
            // 8 内置头像网格（横向）—— 选中即换头像：同时清掉自定义图
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(avatarEmojis) { i, e ->
                    val bg = avatarPalette[i % avatarPalette.size]
                    Box(
                        Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(android.graphics.Color.parseColor(bg)))
                            .border(if (e == avatarEmoji && syncedBmp == null) 2.dp else 0.dp, TGColors.Ink, androidx.compose.foundation.shape.CircleShape)
                            .clickable {
                                avatarEmoji = e
                                prefs.edit().putString("avatar_emoji", e).apply()
                                // v5.15.7：推给电脑端（桌面显示同款 emoji）+ 清掉自定义图，双端头像一致
                                vm.setSyncedSetting("avatar_emoji", e)
                                vm.setSyncedSetting("avatar_img", "")
                            },
                        contentAlignment = Alignment.Center
                    ) { Text(e, fontSize = 17.sp) }
                }
            }
        }
```

## 八、⚠️ 恢复时的两个坑（本版真踩到）

### 1. 顶层事件绑定**必须**空保护 → 否则整页停摆 ⭐⭐

下架时把 `#profileAvatarFile` 从 HTML 删了，但 JS 顶层还留着

```js
document.getElementById('profileAvatarFile').addEventListener('change', ...)
```

元素拿不到 → 抛 `TypeError` → **顶层代码从此中断** → 后面的
`if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', startApp)`
**根本没注册** → `startApp()` 永远不被调用。

**症状**（很好认）：界面停在 `index.html` 里的静态死值 —— 问候语一直是「下午好，boss」、
积分一直是「409 分」、点什么都没反应。而 `typeof startApp` 仍是 `'function'`（函数声明会 hoist），
所以**光看"函数在不在"判断不出来**。

**判据**：手动执行 `startApp()` 若报 `Cannot access '_xxx' before initialization`，
就是这个坑 —— 说明顶层在**某个 let/const 之前**就抛错中断了。

### 2. 删 HTML 元素后，跑一次"引用存在性检查"

```bash
cd task-guide
python -c "import io,re; js=io.open('desktop/ui/app3.js',encoding='utf-8').read(); html=io.open('desktop/ui/index.html',encoding='utf-8').read(); ids=set(re.findall(r\"getElementById\('([^']+)'\)\",js)); have=set(re.findall(r'id=\"([^\"]+)\"',html)); print(sorted(i for i in ids if i not in have))"
```

输出里如果出现**刚删掉**的元素名，就要回去给它加空保护（或把元素放回来）。
