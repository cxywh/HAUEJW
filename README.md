# 教务助手（HAUEJW）

河南工程学院教务系统的第三方安卓客户端。Kotlin + Jetpack Compose 原生应用，单 APK 约 1.3 MB。

> 非官方项目，与河南工程学院无隶属关系。仅供个人学习自用，禁止商业用途与二次分发。详见 [NOTICE.md](NOTICE.md)。

## 功能

- **课表**：按周显示，**左右滑动即可切换周次**，也可用箭头或周次条；支持单双周、集中实践课；点课程卡片看教师/教室/学分/考核等详情；课表本地缓存，进不去校园网也能看上次的
- **课前提醒**：每节课开课前 10/20/30/45 分钟提醒，两周内的课自动排好，重启手机后自动重排。两种方式可选：普通消息通知，或整屏闹钟（静音也会响、自动亮屏）
- **成绩与考试**：原生页面直接读教务系统的接口，不用在网页里翻
- **本地修改**：可在手机上改课程显示名、把某次课从某一周或本学期移除、调整上课时间（只改一次或整学期）。改动只存本机，不影响教务系统数据，可随时撤销
- **导入 / 导出**：课表支持 JSON / CSV / ICS 导入（按内容自动识别），可导出为 JSON 或图片
- **切换账号**：登录页列出用过的账号，点一下直接登录；每个账号的密码按学号分开加密存放，删一个不影响其他
- **外观**：浅色/深色主题，多套配色

## 运行要求

- Android 8.0（API 26）及以上，目标 API 35
- 能访问学校教务系统 `http://124.system.haue.edu.cn/jwglxt/`

## 下载

到 [Releases](../../releases) 下载最新的 `HAUEJW-<版本>.apk`，传到手机安装即可。

APK 使用个人签名（CN=HAUEJW）。安卓提示"未知来源"时，在系统设置里允许安装即可。

## 隐私与安全

- 账号密码只在你手机本地保存：记住密码用 Android Keystore 派生密钥做 AES-GCM 加密，登录会话 Cookie 同样加密存本机
- 不上传任何东西：没有统计分析、没有广告、没有第三方追踪
- 明文 HTTP 只对学校域名放行，其它一律禁止
- 应用只读取数据（课表、成绩、考试），不向学校系统提交任何修改

## 自己编译

```bash
cd HAUEJW
./gradlew.bat assembleDebug      # 调试包，可与正式版共存
./gradlew.bat assembleRelease    # 正式包（R8 压缩，需要 keystore.properties）
```

发布用的签名密钥不在仓库里（`.gitignore` 已排除 `keystore/` 与 `keystore.properties`）。自己编译正式包时准备一份自己的即可：

```properties
storeFile=keystore/你的.jks
storePassword=你的口令
keyAlias=你的别名
keyPassword=你的口令
```

Windows 上 release 报 `classes.dex 另一个程序正在使用` 时：`./gradlew.bat --stop`，删掉 `app/build/intermediates/dex/release` 再构建。

## 代码结构

| 文件 | 作用 |
|---|---|
| `JwApi.kt` | 教务系统接口（登录 / 课表 / 成绩 / 考试 / 会话 / 自检） |
| `Models.kt` | 数据模型（Course / Term / Timetable / Student） |
| `WeekUtils.kt` | 周次解析、节次解析、周↔日期换算、节次时间表 |
| `Reminder.kt` | 课前提醒（排程 / 通知渠道 / 整屏闹钟 / 开机重排） |
| `AlarmActivity.kt` | 整屏闹钟界面 |
| `Board.kt` | 课表界面主体 |
| `Panes.kt` | 各设置面板（周次、主题、提醒、本地修改等） |
| `MainActivity.kt` | 入口与面板分发 |
| `Diag.kt` | 本地诊断（只记位置标签与异常类名，不记内容） |
| `Prefs.kt` | 本地存储（学号、课表缓存、提醒设置、界面偏好） |
| `Theme.kt` | 配色 |
| `SecretStore.kt` | 密码与会话的本地加密存储（按学号分账号） |
| `TimetableImage.kt` | 课表导出图片 |
| `Edits.kt` / `ImportExport.kt` | 本地修改与课表导入导出（JSON / CSV / ICS） |

单元测试在 `app/src/test/`（30 个：课表解析 / 周次换算 / 提醒排程 / 诊断缓冲）。

## 更多

接口映射、构建细节、改动指南见 [docs/开发文档.md](docs/开发文档.md)。
