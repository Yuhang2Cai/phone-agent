# 将已下载的 Figma 路径资产转换为 Android VectorDrawable；不修改路径或手工重画图标。
# 输入固定资产映射，输出原生 drawable；仅支持本确认稿使用的无变换 path，结构变化立即拒绝。
$ErrorActionPreference = 'Stop'
$rnRoot = Split-Path -Parent $PSScriptRoot
$repositoryRoot = Split-Path -Parent $rnRoot
$mapping = @{ '3b437'='home'; '677c9'='ai'; 'c72f8'='orders'; '59f3c'='activity'; 'e1d6b'='profile' }
foreach ($entry in $mapping.GetEnumerator()) {
    # 输入校验：任意变换或非路径几何需要重新评估转换方式，不能静默丢失画面。
    [xml]$svg = Get-Content -LiteralPath "$rnRoot/src/assets/$($entry.Key).svg" -Raw
    if ($svg.SelectNodes('//*[@transform]').Count -ne 0) { throw '图标含变换，不能直接转换' }
    $paths = $svg.SelectNodes('//*[local-name()="path"]')
    if ($paths.Count -eq 0) { throw '图标缺少路径' }
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('<?xml version="1.0" encoding="utf-8"?>')
    $lines.Add("<!-- 已确认 Figma $($entry.Key).svg 自动转换；描边颜色由底栏选中态统一着色。 -->")
    $lines.Add('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">')
    foreach ($path in $paths) {
        # 数据规范化：只映射 Android 支持的同义字段，保持 SVG 的默认端点与连接方式。
        $attributes = 'android:pathData="' + $path.GetAttribute('d') + '" android:fillColor="#00000000"'
        foreach ($pair in @(@('stroke','strokeColor'), @('stroke-width','strokeWidth'), @('stroke-linecap','strokeLineCap'), @('stroke-linejoin','strokeLineJoin'))) {
            $value = $path.GetAttribute($pair[0])
            if ($value) { $attributes += ' android:' + $pair[1] + '="' + $value + '"' }
        }
        $lines.Add('    <path ' + $attributes + ' />')
    }
    # 输出仅限本仓库已知资源目录，文件名来自上面的固定映射。
    $lines.Add('</vector>')
    [IO.File]::WriteAllLines("$repositoryRoot/android-app/app/src/main/res/drawable/ic_tab_$($entry.Value).xml", $lines)
}
