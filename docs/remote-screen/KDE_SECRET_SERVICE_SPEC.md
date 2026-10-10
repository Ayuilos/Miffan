# KDE：krdp 凭据改走 Secret Service（Codex 规格）

## 问题（真机复现，2026-10-10）

用户的 CachyOS 同时装了 GNOME 和 KDE。用密码登录 Plasma 后，App 报 `keyring_locked`，但钥匙串其实已解锁。

- SDDM 的 PAM 同时有 `pam_gnome_keyring auto_start` 和 `pam_kwallet5 auto_start`。登录 KDE 时，gnome-keyring-daemon（10:33:13 与 sddm-helper 同时启动）先拿到了 `org.freedesktop.secrets`，它的默认集合 `/org/freedesktop/secrets/collection/login` 是 `Locked=false`。
- ksecretd（KDE 的 Secret Service 提供者，经 `--pam-login` 启动）也在运行，它的 `kdewallet` 集合 `Locked=false`，但只拿到 `org.kde.ksecretd` 和 `org.kde.secretservicecompat`，没有拿到 `org.freedesktop.secrets`。
- KWallet 6.30 的 `kwalletd6` 是建立在 Secret Service 之上的 KWallet API 外壳（由 D-Bus 按需激活）。`qdbus6 org.kde.kwalletd6 /modules/kwalletd6 networkWallet` 返回 `kdewallet`，`isOpen kdewallet` 返回 `false`，因为当前 Secret Service 提供者（gnome-keyring）里没有名为 kdewallet 的集合。
- 所以 `miffan.sh` 的 `rdp_kde()` 用 KWallet API 判断解锁、用 `kwallet-query` 写入，在这种混装环境下必然失败；就算写进去，krdp 用 `QTKEYCHAIN_BACKEND=kwallet6` 读的也是同一层外壳。

## 要求

- KDE 下改为通过 Secret Service（libsecret）保存和读取 krdp 密码：
  - 判断：`org.freedesktop.secrets` 的 `default` 别名存在，且该集合 `Locked=false`。否则返回 `keyring_locked`。不得创建集合或解锁集合，不得写入无密码集合。
  - 写入：用 `secret-tool store`（密码只经 stdin），属性必须与 QtKeychain 0.17 的 libsecret 后端在 `ReadPasswordJob("KRDP")` 时的查找完全一致（schema 名与 `xdg:schema` 属性、`user`、`server`、`type` 等）。请按 qtkeychain 0.17 源码确认，不要猜。
  - 启动 krdp 时用 `QTKEYCHAIN_BACKEND=libsecret`，不用 `-u/-p`。其余保持 P5b 的做法：独立 `XDG_CONFIG_HOME`、只监听 127.0.0.1、`--plasma`、自己的 transient unit、owner/digest 记录、拒绝覆盖用户已有的配置。
  - 是否保留 KWallet 路径作为后备由你判断；保留的话说明理由。
- GNOME 路径不变。
- `miffan probe` 可以增加 Secret Service 状态（提供者进程名、默认集合是否解锁），只读，供界面展示。
- 升 `MIFFAN_HELPER_VERSION`。

## 你负责的文件

- `app/src/main/assets/remote/miffan.sh`、`rdp/scripts/test-helper.py`、相关 JVM 测试、`docs/remote-screen/P5B_NOTES.md` 追加一节。
- 不改 `app/src/main/java/me/ayuilos/miffan/ui/`。在 `fix/rdp-kde-secret-service` 分支上提交，不要合并、rebase 或推送其他分支。

## 验收

1. `python3 rdp/scripts/test-helper.py` 覆盖：默认集合解锁时写入（stdin、argv 无密码）；锁定或没有默认别名时返回 `keyring_locked`；不创建集合；krdp 的环境变量是 libsecret。
2. `./gradlew :app:testDebugUnitTest :app:compileDebugKotlin` 通过。
3. 真机验证由 Claude 和用户在用户的 KDE 会话里做。完成后告诉 Claude，**不要**自己连接用户的真实账号（ayuilos@100.64.0.5）。
4. notes 写清楚：根因、属性如何与 QtKeychain 对齐（附源码出处）、GNOME/KDE 混装时的行为、纯 KDE（ksecretd 拿到 org.freedesktop.secrets）时的行为。
