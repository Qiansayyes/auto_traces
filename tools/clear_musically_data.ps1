param(
    [string]$PackageName = "com.zhiliaoapp.musically",
    [string]$Serial = "",
    [switch]$SkipForceStop
)

$ErrorActionPreference = "Stop"

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Args
    )

    if ([string]::IsNullOrWhiteSpace($Serial)) {
        & adb $Args
    } else {
        & adb -s $Serial $Args
    }
}

try {
    $null = Get-Command adb -ErrorAction Stop
} catch {
    Write-Error "未找到 adb，请先安装并配置到 PATH。"
    exit 1
}

Write-Host "== 检查设备连接 =="
if ([string]::IsNullOrWhiteSpace($Serial)) {
    & adb devices
} else {
    & adb devices | Out-Host
    Write-Host "目标设备: $Serial"
}

if (-not $SkipForceStop) {
    Write-Host "== 停止应用 =="
    Invoke-Adb "shell su -c `"am force-stop $PackageName`""
}

Write-Host "== 清理应用数据 =="
Invoke-Adb "shell su -c `"pm clear $PackageName`""

Write-Host "== 完成 =="
Write-Host "包名: $PackageName"
if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    Write-Host "设备: $Serial"
}
