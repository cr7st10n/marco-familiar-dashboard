<#
Marco familiar: trae al PC (Windows) las copias cifradas que hace copia-marco.sh en el servidor.

- Solo descarga las que faltan (o las que llegaron incompletas), primero a %TEMP%
  y luego las mueve al destino: la carpeta de la nube nunca ve un archivo a medias.
- Borra en el destino las copias con más de -Semanas semanas, pero deja SIEMPRE
  las -Minimo más recientes. No borra nada por el hecho de que falte en el servidor:
  si el servidor se estropea, las copias de la nube no se tocan.
- Usa la llave SSH del usuario (sin contraseña). Si el servidor no responde o el
  destino no existe (p. ej. Google Drive aún no ha arrancado), sale sin hacer nada.

Uso: pwsh -NoProfile -File recoger-copias.ps1 -Destino 'H:\Mi unidad\Personal\Copias marco'
#>
param(
    [Parameter(Mandatory)] [string] $Destino,
    [string] $Servidor = 'familia@servidor-casa',
    [string] $CarpetaRemota = 'copias',
    [int] $Semanas = 8,
    [int] $Minimo = 4,
    [string] $Log = (Join-Path $env:LOCALAPPDATA 'copias-marco\recoger.log')
)
$ErrorActionPreference = 'Stop'
$patron = '^copia-marco-(\d{4}-\d{2}-\d{2})(_\d{4})?\.tar\.gz\.gpg$'

New-Item -ItemType Directory -Force (Split-Path $Log) | Out-Null
function Escribe([string] $texto) {
    $linea = "{0:yyyy-MM-dd HH:mm:ss}  {1}" -f (Get-Date), $texto
    Add-Content -Path $Log -Value $linea -Encoding utf8
    Write-Output $linea
}
# El log no crece sin fin: se queda con las últimas 500 líneas
if ((Test-Path $Log) -and (Get-Content $Log).Count -gt 500) {
    Set-Content $Log (Get-Content $Log -Tail 500) -Encoding utf8
}

if (-not (Test-Path $Destino)) { Escribe "No existe el destino '$Destino'; se intentará la próxima vez"; exit 1 }

# Lista remota: "nombre tamaño" por línea
$lista = & ssh -o BatchMode=yes -o ConnectTimeout=20 $Servidor "cd ~/$CarpetaRemota 2>/dev/null && stat -c '%n %s' copia-marco-*.tar.gz.gpg 2>/dev/null"
if ($LASTEXITCODE -ne 0 -and -not $lista) { Escribe "El servidor no responde o no hay copias; se intentará la próxima vez"; exit 1 }

$traidas = 0
foreach ($fila in @($lista)) {
    $nombre, $tam = $fila -split ' ', 2
    if ($nombre -notmatch $patron) { continue }
    $tam = [int64]$tam
    $local = Join-Path $Destino $nombre
    if ((Test-Path $local) -and (Get-Item $local).Length -eq $tam) { continue }

    $tmp = Join-Path $env:TEMP "$nombre.parcial"
    & scp -q -o BatchMode=yes -o ConnectTimeout=20 "${Servidor}:$CarpetaRemota/$nombre" $tmp
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tmp) -or (Get-Item $tmp).Length -ne $tam) {
        Remove-Item $tmp -ErrorAction SilentlyContinue
        Escribe "ERROR al traer $nombre; se intentará la próxima vez"
        continue
    }
    Move-Item $tmp $local -Force
    Escribe ("Traída {0} ({1:N0} MB)" -f $nombre, ($tam / 1MB))
    $traidas++
}

# Limpieza por antigüedad, respetando siempre las $Minimo más recientes
$copias = Get-ChildItem $Destino -File | Where-Object Name -match $patron |
    Sort-Object { [datetime]::ParseExact(([regex]::Match($_.Name, $patron)).Groups[1].Value, 'yyyy-MM-dd', $null) }, Name -Descending
$limite = (Get-Date).Date.AddDays(-7 * $Semanas)
$copias | Select-Object -Skip $Minimo | ForEach-Object {
    $fecha = [datetime]::ParseExact(([regex]::Match($_.Name, $patron)).Groups[1].Value, 'yyyy-MM-dd', $null)
    if ($fecha -lt $limite) { Remove-Item $_.FullName; Escribe "Borrada por antigua: $($_.Name)" }
}

if ($traidas -eq 0) { Escribe "Al día: no había copias nuevas" }
