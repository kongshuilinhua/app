# 柑橘采集与病虫害识别

Android 提供独立病虫害识别与数据采集两条流程；Web 用于专业核验和训练数据准备，共用 FastAPI 服务。

| 目录 | 内容 |
| --- | --- |
| `android/` | Kotlin、CameraX、Room 采集客户端 |
| `admin/` | Vue 3、TypeScript 管理端 |
| `server/` | FastAPI、元数据与文件适配、测试和数据库DDL |
| `contracts/` | OpenAPI、枚举、JSON Schema和测试夹具 |
| `deploy/` | PostgreSQL配置示例与Nginx配置生成器 |
| `tools/` | 通用启动、网关及契约工具 |

## 本地启动

需要 Python 3.10+、Node.js 20+；Android构建需要JDK17和Android SDK35。

在项目根目录执行（PowerShell）：

```powershell
python -m venv server/.venv
./server/.venv/Scripts/python.exe -m pip install -r server/requirements.txt
npm --prefix admin ci
./tools/run-app.ps1
```

访问 `http://127.0.0.1:8000`。开发默认SQLite，数据保存在`.runtime/`。首次空库会创建演示账号`super`、`admin`、`expert`、`collector1`、`collector2`，演示密码均为`12345678`；它们仅用于本地开发。

也可分别启动API和前端热更新：

```powershell
./server/.venv/Scripts/python.exe -m uvicorn server.app.main:app --host 127.0.0.1 --port 8000
npm --prefix admin run dev
```

Linux/macOS使用`server/.venv/bin/python`替代Windows Python路径；`npm --prefix admin run build`后API可同源提供管理页面。管理端默认连接真实API，显式设置`VITE_ENABLE_MOCK=true`才启用模拟。

## 配置与职责

PostgreSQL部署使用受支持版本（已兼容17），参考`deploy/.env.postgres.example`。把配置放在不提交的本地文件，再执行`./tools/run-app.ps1 -ConfigFile <配置路径>`；Linux使用进程环境变量。生产须设置`APP_SEED_DEMO=false`和私有初始密码。

- collector：声明、逐图上传、缺图补传，以及本人采集记录只读。
- expert：专业核验；admin：训练图片质量、排除／恢复、导出；super_admin：跨区及账号管理。
- 元数据通过`MetadataStorage/DataTransaction`访问，图片和ZIP通过`FileStorage`访问；数据库中不保存图片字节。
- 默认单API进程：上传最多4个在途请求；导出同时1批、每账号每分钟6批、最多5000图／候选样本、ZIP不超过1GiB。满额429、超范围413；异常重启后可按原快照重试导出。
- 生产使用Nginx等HTTPS入口，API／数据库保持私网或回环；网关生成器入口为`python deploy/nginx/render.py --help`。

Android用Android Studio打开`android/`，配置本机SDK后构建；模拟器访问宿主机API通常使用`http://10.0.2.2:8000/api/v1/`。实体设备需使用可达的HTTPS服务地址。

## 检查

```powershell
$env:APP_DATABASE_URL='memory'
./server/.venv/Scripts/python.exe -m pytest server/tests -q -k 'not mysql'
npm --prefix admin run typecheck
npm --prefix admin test
npm --prefix admin run build
./android/gradlew.bat -p android :app:testDebugUnitTest :app:assembleDebug
```

接口变更同步`contracts/openapi.yaml`及相关测试，再运行`npm --prefix admin run generate:types`。DDL基线由`python tools/export-schema.py`生成；切换数据库URL不会自动搬迁数据。运行数据、凭据和本机路径配置不得提交。

## 单张识别

Android 照片缩略图下的“识别此图”连接 `POST /api/v1/recognitions`，返回真实模型候选并保存在该图片的本地记录中。结果不会自动更改类别，需点击“采用结果”；旧的多图记录会只把选中图片单独成条，其他图片不改。已开始上传的记录不能重新识别或采用结果，已保存结果可以只读查看。重拍会清除旧识别结果。Room 2→3 的迁移仅新增图片识别结果字段。

未配置模型时接口返回503，不使用模拟结果。配置 `APP_RECOGNITION_PYTHON` 为已安装 PyTorch/torchvision 的独立 Python，`APP_RECOGNITION_MANIFEST` 为模型清单的绝对路径；主 API 环境不需要安装 PyTorch。清单示例见 [deploy/recognition.example.json](deploy/recognition.example.json)，`weights` 替换为本机训练权重路径，类别顺序必须与训练时一致。当前 worker 支持 torchvision EfficientNet-B0 的 state_dict，用 `weights_only=True` 加载、不下载预训练权重，使用2个CPU线程。

当前接入的是实验性叶片五分类模型，仅供人工复核，不用于果实或虫体。分数不是诊断确定性；无法映射到现有启用字典的类别，采用时使用“不确定”，原始模型建议仍保留。接口只接受采集账号，单图最多20MiB、2400万像素，单并发、推理超时45秒；临时图片在推理结束后清除，不自动加入训练集。

识别前会先跑一次本地 YOLO 检测（SciDB 15 类，`mAP50 0.964`）判断画面里有没有叶片：没有检出叶片类目标时直接返回 `422 NO_LEAF`，不再让闭集分类器强行给类别；检出叶片时按最大叶片框裁剪，再交给五分类模型。检测器不可用时自动退化为纯分类，不阻断主链路。检测器权重与 Python 环境在 `server/config/detector.json` 中填写，并用 `APP_RECOGNITION_DETECT_MANIFEST` 显式启用；默认不部署、不下载模型。分类模型仍由 `APP_RECOGNITION_MANIFEST` / `APP_RECOGNITION_PYTHON` 配置。模型在其他电脑时，本机可先完成手动采集、上传和专家反馈。

## 采集记录粒度

Android 端一张照片就是一条采集记录：每次按下快门立即保存独立记录，品种、部位、类别、症状、备注和识别结果都只属于这一张图片。采集批次承载共同地点、采集人和默认品种，并同步到服务端；批量填写、批量上传不合并记录。旧版多图记录仍保留其原有粒度。


## 采集端功能闭环

- **账号隔离**：本地记录按服务器地址与账号分区；切换账号后不会显示或上传其他账号的数据。Room 4→5 为增量迁移，不删除原图和标注。旧版没有归属信息的记录隔离保留，图片记录页提示数量；不会自动认领给登录者，如需恢复归属应先确认原采集人。
- **后台上传**：确认信息后可逐条、选择多条或按批次加入持久后台队列。联网后自动执行，网络故障/429/5xx退避重试，只补传缺失图片。尚未选择上传的草稿不会因登录而自动提交。换账号/退出后原账号任务暂停，再登录原账号恢复；永久校验失败显示失败，修正后手动重试。首次部署不自动清理本地原图。
- **批次**：支持新建、编辑、关闭/重开和手动云同步/恢复。关闭后不再新增拍摄，已经采集的照片仍可上传。批次脏数据同步失败保留本地；管理端可查看批次、筛选采集记录。换设备恢复的是批次信息，原始照片仍保存在采集设备，已上传图可在管理端查看。
- **审核反馈**：已上传记录可刷新专家核验状态、纠正后的类别、说明与逐图质量结果；离线可查看上次缓存。反馈不覆盖采集时的原始标注。
- **质量建议**：拍摄后在本机检测曝光和细节清晰度，提醒可重拍，也可保留继续。提示与原图一起保存在本地详情。算法是启发式拍摄建议，需按真机/作物校准，不是病害诊断，不检查病害或叶片类别。
- **可选定位**：在相机页主动点击“获取定位”才申请位置权限；拒绝、定位关闭或超时均不阻断拍摄。只记录两分钟内、精度不劣于1000米的定位，不用默认经纬度代替缺失值；位置失效后需重新点击。拍照时仍可使用手填的批次地点。
- **专家咨询**：App“专家咨询”可文字提问并关联本人已上传采集图片；失败保留内容及幂等请求ID。专家或超级管理员在Web“专家咨询”查看附件并真实答复，采集员刷新查看。没有模拟答复或模型自动回答。
- **数据导出**：原有 `labels.csv` 保持格式，新增 `collection.csv` 保存导出快照中的批次和GPS；后续编辑批次不会改变历史导出。

SQLite、PostgreSQL、MySQL使用同一元数据适配；服务启动会非破坏性升级schema 2→3，新增批次/咨询表与可空元数据列。升级生产前应备份数据库和图片目录；不同设备同时编辑同一批次按最后一次同步保存，不自动合并冲突。

Linux开发可构建同源页面后运行：

```bash
npm --prefix admin ci
npm --prefix admin run build
server/.venv/bin/python -m uvicorn server.app.main:app --host 127.0.0.1 --port 18080
```

Android模拟器登录服务地址为 `http://10.0.2.2:18080/api/v1/`，采集演示账号 `collector1`，密码 `12345678`。实体手机需要可达的HTTPS服务；不应继续填写旧电脑路径或模拟器专用地址。正式品种/病害目录由Web基础字典维护，本次不伪造正式类别清单。

## Android 双功能工作流

底部入口为首页、拍照识别、数据采集、我的记录。病虫害识别无需先建采集批次；拍照或从相册导入后，填写作物、部位、树龄与现场现象，经同意上传后调用真实识别服务。识别记录按服务器及账号隔离保存在本机独立数据库，失败保留照片并支持重试。观察日期和备注保存在本机，不发送系统通知。现场现象目前作为本地记录保存，不参与模型或建议匹配。

后台先调用识别模型，再按类别、置信度及适用条件匹配经审核资料，返回通俗建议。App 没有知识库浏览或图谱入口。模型未配置时返回 503，不返回演示病名；资料为空、未审核、结果不确定或条件冲突时，不生成针对性方案。后台资料配置见 [server/config/README.md](server/config/README.md)，默认目录为空，需管理员补充并审核。接口为 `POST /api/v1/diagnoses` 和基于用户绑定签名凭据的 `POST /api/v1/diagnoses/advice`。

识别不会自动创建训练样本。用户明确同意并选择开放采集批次后，才复制成独立待确认采集记录，模型结果不自动成为标签；旧批次、拍摄、标注、上传流程保留在数据采集入口。

## 我的与个人资料

Android 底部为首页、拍照识别、数据采集、记录、我的。个人中心支持读取真实昵称、修改昵称、脱敏手机号、专家咨询、版本信息、隐私与数据说明及退出确认。昵称限制为2至18个字符，只更新当前用户的显示名，不修改登录名、用户ID、角色或照片归属。

个人资料接口为 `GET/PATCH /api/v1/me/profile`。App缓存按服务器及账号隔离；保存失败保留编辑内容，退出仅清理会话和缓存，不删除本地照片或记录。昵称更新后的迟到请求不会覆盖其他账号缓存。

schema 4→5 仅为phone_identities新增可空masked_phone字段。旧手机号记录在下一次成功短信登录时回填脱敏值；回填前界面显示已验证状态，不猜测或展示完整手机号。服务端配置与身份摘要密钥保持不变。升级前备份数据库，旧版代码回滚需与数据库版本配套。

模拟器个人中心真实链路测试入口为 `MyProfileLiveTest`，需显式提供baseUrl及测试采集账号；测试覆盖昵称保存与取消、咨询/关于/隐私入口、退出及服务端会话撤销、重新登录后记录保留，并恢复原昵称。不发送短信。

## 独立本地调试（暂不部署或输出交付包）

本地调试 App 使用独立包名 `com.example.citruscollect.local`，与现有 App 的账号缓存、照片和 Room 数据分开。仅 debug 构建在传入 `-PcitrusLocalDebug=true` 时连接 `http://10.0.2.2:18081/api/v1/`；release 始终保持原 HTTPS 配置。

1. 单独终端运行 `./tools/run-local-debug-server.ps1`。API 只监听127.0.0.1:18080，数据位于`.runtime/local-debug`，明确关闭真实短信发送。
2. 使用本机 Nginx 运行 `./tools/run-gateway.ps1 -NginxExe <nginx.exe路径> -Port 18081 -ApiPort 18080 -RuntimeDir .runtime/local-debug/gateway`。本地 App 通过网关而非直连 API，覆盖请求体限制等行为。
3. 运行 `./android/gradlew.bat -p android :app:installDebug -PcitrusLocalDebug=true`，在模拟器打开“柚见·本地调试”。使用本地采集账号collector1、示例密码12345678。

批次照片拍摄后的draft显示“待确认”；缺少照片或品种/部位/类别值时才显示“待完善”及具体缺项。unknown“不确定”是有效选择，症状、备注、GPS等可选项不作为缺项。确认保存后显示“待上传”，不会自动把未确认照片放入上传队列。

图片上传、单张识别与独立诊断网关上限均为20MiB；诊断建议等普通JSON请求仍为1MiB。图片像素上限仍由API独立校验。配置真实本地Nginx路径到TEST_NGINX_EXE后，可运行`server/tests/test_nginx_image_limits.py`验证限额与路由，不会连接云端或发送短信。
