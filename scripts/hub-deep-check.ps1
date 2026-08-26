param()
$ErrorActionPreference = "Continue"
# 固化检查：对齐 a0.11.17.x 当前代码锚点（推送核心优先/输入料静态保护/电力硬门控+探测/计费平滑/同窗统计/路径过滤）
$hub     = "src/silicon/world/blocks/distribution/ItemTransferHub.java"
$routing = "src/silicon/world/blocks/distribution/HubRouting.java"
$text    = Get-Content $hub -Raw -Encoding UTF8
$route   = Get-Content $routing -Raw -Encoding UTF8
function ok([bool]$c,[string]$m){ if($c){ Write-Host "PASS $m" -ForegroundColor Green; return 1 } else { Write-Host "FAIL $m" -ForegroundColor Red; return 0 } }
$pass=0; $total=30
$pass += ok ($text.Contains("HubRouting.isFactory(b)")) "isFactory 委托 HubRouting"
$pass += ok ($route.Contains("Reconstructor.ReconstructorBuild")) "isFactory 含重构工厂"
$pass += ok (-not $route.Contains("if (other.items != null) return true")) "白名单无『有物品栏即连』泛化"
$pass += ok ($text.Contains("producer.block.consumesItem(item))")) "推送输入料保护门（静态配方判定）"
$pass += ok ($text.Contains("ammoTypes.get(b).damage")) "炮台伤害优先"
$pass += ok ($text.Contains("blocked = false")) "push 堵线触发"
$pass += ok ($text.Contains("coreHasRoomFor(core, item)")) "核心余量按真实容量（storageCapacity，防焚烧）"
$pass += ok ($text.Contains("directTransfer(producer, core, item, 10);")) "矿机/工厂产物核心未满即推"
$pass += ok ($text.Contains("item.id >= consumer.items.length()")) "越界防护"
$pass += ok ($text.Contains("power.status < POWER_OK")) "电力硬门控（不足完全停止）"
$pass += ok ($text.Contains("STARVE_COOLDOWN_TICKS") -and $text.Contains("probing = true")) "欠压冷却+供电探测恢复"
$pass += ok ($text.Contains("timer(0, 10)")) "调度节流 6Hz"
$pass += ok ($text.Contains("private void chargeOne(")) "chargeOne 单跳计费"
$pass += ok ($text.Contains("transferCount += transferCountNext") -and $text.Contains("smoothBuf[smoothIdx] += powerConsumedNext")) "延迟计费/计数帧首并入（计数累加语义）"
$pass += ok ($text.Contains("smoothSum() / SMOOTH_TICKS")) "瞬时请求平滑（摊平批量突发）"
$pass += ok ($text.Contains("powerSecondWindow.add(actualPower)")) "耗电按秒(60t窗口)/速率10s窗口统计"
$pass += ok ($route.Contains("linkedCore != null")) "核心旁已合并仓库排除（linkedCore 判据）"
$pass += ok ($text.Contains("write.i(network.id)") -and $text.Contains("revision < 1")) "存档序列化 v1"
$pass += ok ($text.Contains("寻找其它中枢直连的仓库")) "核心满回退仓库跨网 BFS"
$pass += ok ($text.Contains("world.isGenerating()")) "加载期防误删链接"
$pass += ok ($text.Contains("bfsInit")) "BFS 池化复用"
$pass += ok ([regex]::Matches($text, "!relayable\(").Count -ge 6) "BFS 路径过滤欠压/禁用中枢"
$pass += ok ($text.Contains("!relayable(srcHub)") -and $text.Contains("!relayable(dstHub)")) "端点归属枢可中转校验"
$pass += ok ($text.Contains("b == producer) continue")) "仓库落点自排除（防自投自收）"
$pass += ok ($text.Contains("isProducer(b) && !b.block.consumesItem(item)")) "供源仅产出物——不抽工厂输入料"
$pass += ok (-not $text.Contains("isInputStockOfFactory")) "无兜底动用工厂输入库存（pass3 已删）"
$pass += ok ($text.Contains("public static final Color hubLinkColor")) "中枢间粉色连线常量（ff88dd）"
$pass += ok ([regex]::Matches($text, "lineColorFor\(").Count -ge 3) "连线颜色按目标类型三处共用（常驻/预览/规划虚线）"
$pass += ok ($text.Contains("cur == b") -and $text.Contains("nb == b")) "同网判定含跨枢中枢本身（紫色不漏判）"
$pass += ok ([regex]::Matches($text, "Pal\.reactorPurple").Count -ge 2) "网络内紫色标记（单击+放置预览两处）"
Write-Host "--- $pass/$total ---" -ForegroundColor Cyan
if($pass -ne $total){ exit 1 }
# 编译（JDK17：build-tools 34 d8 需要）
$env:JAVA_HOME = "C:\Users\56308\.jdks\jbr-17.0.7"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
& ".\gradlew.bat" deploy --console=plain "-Dorg.gradle.java.home=$env:JAVA_HOME" | Out-Null
if($LASTEXITCODE -ne 0){ Write-Host "BUILD FAIL" -ForegroundColor Red; Pop-Location; exit 1 }
Write-Host "BUILD SUCCESS" -ForegroundColor Green
# 部署一致性：最新产物同步到两个游戏模组目录
# ① %APPDATA%\Mindustry\mods（默认数据目录）
# ② D:\Games\Mindustry-HotReload\data\mods（热重载启动器 MINDUSTRY_DATA_DIR 指向的目录——漏掉它游戏会一直加载旧包）
$jar = Get-ChildItem "build/libs/Silicon-*-v159.7.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Copy-Item $jar.FullName "Silicon.mod.jar" -Force
Write-Host "已部署 Silicon.mod.jar ($((Get-Item 'Silicon.mod.jar').Length) bytes)"
$targets = @(
  (Join-Path $env:APPDATA "Mindustry\mods"),
  "D:\Games\Mindustry-HotReload\data\mods"
)
foreach ($dir in $targets) {
  if (-not (Test-Path $dir)) { Write-Host "跳过不存在的模组目录: $dir" -ForegroundColor Yellow; continue }
  # 移除全部旧版 Silicon 包防同模组双载，替换为最新构建
  Get-ChildItem $dir -Filter 'Silicon*.jar' | Remove-Item -Force
  Copy-Item $jar.FullName (Join-Path $dir 'Silicon.jar') -Force
  # 校验同步后 jar 内的版本号，防止旧包滞留
  $vtmp = Join-Path $env:TEMP ("si-ver-check-" + [guid]::NewGuid().ToString("N").Substring(0,8))
  New-Item -ItemType Directory -Force $vtmp | Out-Null
  Push-Location $vtmp
  jar xf (Join-Path $dir 'Silicon.jar') mod.hjson
  $ver = (Select-String -Path (Join-Path $vtmp 'mod.hjson') -Pattern '^version:').Line.Trim()
  Pop-Location
  Remove-Item $vtmp -Recurse -Force -ErrorAction SilentlyContinue
  Write-Host "已同步 $dir\Silicon.jar ($ver, $((Get-Item (Join-Path $dir 'Silicon.jar')).Length) bytes)" -ForegroundColor Green
}
Pop-Location
