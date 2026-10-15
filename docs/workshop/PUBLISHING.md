# 发布到 Steam 创意工坊

Necesse 没有独立的上传工具，上传在**游戏内**完成：用开发模式启动游戏，然后在「Mod」页面对开发模组点「上传到 Steam 创意工坊」。流程依据反编译的 `SteamDevModProvider` 和 `LoadedDevMod`（游戏 1.3.3）整理，与 Coop Pause、Team Map Sync 相同。

## 材料清单

| 文件 | 用途 |
|---|---|
| `src/main/resources/preview.png` | 640×640 预览图，打进 jar 的 `resources/preview.png`，上传时自动作为创意工坊封面。**jar 里没有这张图时，游戏不显示上传按钮**（`SteamDevModProvider` 中 `mod.preview != null` 是显示条件） |
| `docs/workshop/description.bbcode` | 创意工坊描述（Steam BBCode，中英双语，约 4700 字符；游戏上传框上限 5000 字符） |
| `docs/workshop/images/01-nack.png`、`02-results.png`、`03-kcp.png` | 1280×720 功能介绍图，在 Steam 网页上作为附加图片 |
| `tools/make_workshop_images.py` | 重新生成预览图和功能卡片：`python tools/make_workshop_images.py .`（需要 Pillow 和 Windows 自带字体）。柱状图的数据写在脚本开头的 `RESULTS` 里，来自 `docs/RESULTS.md` 第 2 轮的平均值 |

> 卡片是示意图，不是游戏截图。这个 Mod 没有可见的界面，可以考虑补一张 `/netrel status` 输出的截图。

## 首次发布

1. **构建**（Gradle 9 需要 JDK 17 以上；`mods/` 里要有 Mod Settings Lib 的 jar，见 README）：
   ```bash
   JAVA_HOME=/g/scoop/apps/temurin17-jdk/current ./gradlew buildModJar test
   tools/check-internals.sh
   ```
   确认 `build/jar/` 里**只有一个 jar**。游戏要求开发模组目录中除 `preview.png` 外只能有一个 jar 文件、不能有子目录（`validateDevFolderAndReturnJar`）。`buildModJar` 每次会清掉旧文件，正常情况下满足要求。

2. **以开发模式启动游戏**（Steam 必须正在运行）：
   ```bash
   JAVA_HOME=/g/scoop/apps/temurin17-jdk/current ./gradlew runClient
   ```
   等价于 `-dev -mod "build/jar/"`。从 Steam 直接启动不会带 `-mod`，Mod 列表里就看不到它。

3. 主菜单进入 **Mod**，选中 **Net Reliability**（显示「从开发文件夹加载」），点 **「上传到 Steam 创意工坊」**。

4. 在弹出的窗口中：
   - 选择 **「创建新项目」**。
   - 勾选「更新描述」，粘贴 `description.bbcode` 的内容。不勾选的话，游戏默认填 `mod.info` 里的英文简短描述。
   - 标签建议勾选 **Tweaks**，可以再加 **Miscellaneous**。游戏只提供这几个：Translation / Texture pack / Interface / New features / New content / Tweaks / Miscellaneous。
   - 点「继续」，同意 Steam 创意工坊服务条款，确认内容是你自己创作的。

5. 看到「成功地创建/更新了模组」后，到 Steam 网页上完成其余设置（Steam 客户端 → 社区 → 创意工坊 → 你的文件）：
   - **必需物品**：「添加/移除必需物品」→ 添加 **Mod Settings Lib**（`3558238376`）。订阅者会被提示同时订阅它。
   - **图片和视频**：上传 `docs/workshop/images/` 下的 3 张图。
   - **可见性**：建议先设为「仅好友」，和朋友实际联机测一次（尤其是 Steam 好友加入，这条路径还没实测过），再改成「公开」。
   - 新上传的物品需要接受过 Steam 创意工坊法律协议，否则只有你自己能看到。

6. 记下物品 ID（网址中的 `?id=...`），补到 README 里。

## 发布更新

1. 在 `build.gradle` 中提高 `modVersion`，例如 `1.0` → `1.1`。版本号变化后，服务端和客户端的 Mod 一致性检查会要求所有玩家都更新。改了 NACK 包格式的话，还要遵守 `docs/PROTOCOL.md` 的兼容规则。
2. `./gradlew buildModJar`，然后 `./gradlew runClient`。
3. Mod → Net Reliability → 上传 → 在下拉框中**选择已有的项目**。
4. 「更新描述」：如果你在 Steam 网页上改过描述，**取消勾选**，否则会被覆盖。
5. 游戏会自动把更新说明写成 `Mod version <版本> for game version <游戏版本>`，更详细的说明可以在 Steam 网页的「更新日志」里编辑。

## 首个版本的更新日志参考

```
1.0
- Lost fragments of split packets are resent at once (NACK), instead of the game re-requesting after 2 s
- Fixes a vanilla bug: a split packet whose first fragment arrives late never completes
- Larger UDP socket buffers
- Experimental KCP transport for joining by IP (kcpEnabled, off by default)
- /netrel and /netrelc status, CSV stats, simulated loss for testing
```

## 发布前自检

- [ ] `./gradlew test` 和 `tools/check-internals.sh` 全部通过
- [ ] `tools/net-test.sh smoke` 通过（独立服务器 + 客户端直连）
- [ ] 主持世界，让朋友通过 **Steam 好友**加入：能正常游玩，`/netrelc status` 里 `Hooks seen` 的 `steamSend=true`
- [ ] 至少一次真实网络（非本机）的直连 IP 联机，分别在 `kcpEnabled` 关和开的情况下
- [ ] 独立服务器（`StartServer-nogui.bat` 或 `./gradlew runServer`）能正常加载。日志里的 `SteamSendPatch ... likely outdated mod` 是预期的
- [ ] 进 设置 → Mod 设置 → Net Reliability：每个选项都显示中文/英文文字（不是 `netrel...` id），改一项保存后 cfg 文件跟着变
- [ ] 独立服务器的 `mods/` 文件夹里也要放 Mod Settings Lib 的 jar
- [ ] `build/jar/` 中只有一个 jar，jar 内包含 `resources/preview.png`、`resources/locale/*.lang` 和 `mod.info`（其中 `dependencies = [aizsave.modsettingslib]`）
