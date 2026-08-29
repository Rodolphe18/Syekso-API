# Verification de bascule Ktor -> Spring. A lancer APRES avoir demarre le serveur Spring.
#   powershell -ExecutionPolicy Bypass -File smoke-cutover.ps1
#   powershell -ExecutionPolicy Bypass -File smoke-cutover.ps1 -Port 8081 -ReadOnly
#
# -ReadOnly saute l'aller-retour du PIN, seule partie du script qui ecrit dans la base.
# Chaque test affiche OK ou ECHEC ; code de sortie non nul si quelque chose casse.
# Volontairement en ASCII pur : PowerShell 5.1 lit un .ps1 sans BOM en ANSI et casse sur les accents.

param(
    [int]$Port = 8080,
    [switch]$ReadOnly
)

$ErrorActionPreference = 'Stop'
$base        = "http://localhost:$Port"
$intercomKey = 'syekso-demo-intercom-key'   # doit correspondre a syekso.intercom-key
$failures    = 0

function Check($name, $block) {
    try {
        if (& $block) { Write-Host "  OK      $name" -ForegroundColor Green }
        else { Write-Host "  ECHEC   $name" -ForegroundColor Red; $script:failures++ }
    } catch {
        Write-Host "  ECHEC   $name  -> $($_.Exception.Message)" -ForegroundColor Red
        $script:failures++
    }
}

# Rend le code HTTP sans lever d'exception sur 4xx/5xx.
# Pas de -SkipHttpErrorCheck : ce parametre est PowerShell 7. En 5.1 un 401 leve une WebException,
# et le code se lit sur la reponse qu'elle transporte.
function Status($method, $path, $headers, $body) {
    try {
        $p = @{ Method = $method; Uri = "$base$path"; Headers = $headers; UseBasicParsing = $true }
        if ($body) { $p.Body = $body; $p.ContentType = 'application/json' }
        [int](Invoke-WebRequest @p).StatusCode
    } catch {
        if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { -1 }
    }
}

Write-Host ""
Write-Host "== Le serveur repond ($base) ==" -ForegroundColor Cyan
Check "GET /health rend status=ok" { (Invoke-RestMethod "$base/health").status -eq 'ok' }

Write-Host ""
Write-Host "== Le login emet un jeton ==" -ForegroundColor Cyan
$login = Invoke-RestMethod -Method Post -Uri "$base/auth/login" -ContentType 'application/json' `
         -Body '{"email":"rodolphe@example.com","password":"password"}'
$token = $login.token
Check "POST /auth/login rend un token et l'utilisateur" { $token -and $login.user.id -eq 'user-rodolphe' }
Check "la reponse ne contient aucun hash de mot de passe" {
    -not ($login.user.PSObject.Properties.Name -contains 'passwordHash')
}
Check "mauvais mot de passe -> 401" {
    (Status POST '/auth/login' @{} '{"email":"rodolphe@example.com","password":"faux"}') -eq 401
}

Write-Host ""
Write-Host "== Le jeton ouvre les routes resident ==" -ForegroundColor Cyan
$auth = @{ Authorization = "Bearer $token" }
Check "GET /me/doors -> 200"           { (Status GET '/me/doors' $auth $null) -eq 200 }
Check "GET /me/doors sans jeton -> 401" { (Status GET '/me/doors' @{} $null) -eq 401 }
Check "GET /me/pin-codes -> 200"       { (Status GET '/me/pin-codes' $auth $null) -eq 200 }
Check "GET /me/invitations -> 200"     { (Status GET '/me/invitations' $auth $null) -eq 200 }

Write-Host ""
Write-Host "== Les portes du seed sont la (demo materielle) ==" -ForegroundColor Cyan
$doors = (Invoke-RestMethod -Uri "$base/me/doors" -Headers $auth).doors
Check "la porte d'entree annonce OSKEY-HALL-01" {
    # Aucune porte = le compte n'a pas encore active MONT-2026. Normal sur une base neuve.
    ($doors.Count -eq 0) -or ($doors.bleLocalName -contains 'OSKEY-HALL-01')
}

Write-Host ""
Write-Host "== Les deux chaines de securite sont bien separees ==" -ForegroundColor Cyan
$key = @{ 'X-Intercom-Key' = $intercomKey }
Check "GET /intercom/directory avec la cle -> 200" {
    (Status GET '/intercom/directory?buildingId=bld-montmartre' $key $null) -eq 200
}
Check "GET /intercom/directory sans la cle -> 401" {
    (Status GET '/intercom/directory?buildingId=bld-montmartre' @{} $null) -eq 401
}
Check "le jeton resident n'ouvre PAS l'interphone -> 401" {
    (Status GET '/intercom/directory?buildingId=bld-montmartre' $auth $null) -eq 401
}
Check "la cle interphone n'ouvre PAS /me/doors -> 401" {
    (Status GET '/me/doors' $key $null) -eq 401
}
Check "POST /intercom/validate code inconnu -> allowed=false" {
    (Invoke-RestMethod -Method Post -Uri "$base/intercom/validate" -Headers $key `
     -ContentType 'application/json' -Body '{"pin":"000000"}').allowed -eq $false
}

Write-Host ""
Write-Host "== Le PIN fait l'aller-retour resident -> interphone ==" -ForegroundColor Cyan
if ($ReadOnly) {
    Write-Host "  IGNORE  -ReadOnly : aucune ecriture dans la base" -ForegroundColor Yellow
} elseif ($doors.Count -eq 0) {
    Write-Host "  IGNORE  aucune porte : active d'abord MONT-2026 depuis l'app" -ForegroundColor Yellow
} else {
    $pin = (Invoke-RestMethod -Method Post -Uri "$base/me/pin-codes" -Headers $auth `
            -ContentType 'application/json' -Body (@{ doorId = $doors[0].id } | ConvertTo-Json)).pin
    Check "un PIN emis est accepte une fois" {
        (Invoke-RestMethod -Method Post -Uri "$base/intercom/validate" -Headers $key `
         -ContentType 'application/json' -Body (@{ pin = $pin } | ConvertTo-Json)).allowed -eq $true
    }
    Check "le meme PIN est refuse la seconde fois (usage unique)" {
        (Invoke-RestMethod -Method Post -Uri "$base/intercom/validate" -Headers $key `
         -ContentType 'application/json' -Body (@{ pin = $pin } | ConvertTo-Json)).allowed -eq $false
    }
}

Write-Host ""
Write-Host "== Le feed refuse un curseur malforme (empreinte du build) ==" -ForegroundColor Cyan
# Ces deux-la rendaient 500 avant le 2026-08-29. Ils servent donc double : ils verifient qu'une
# erreur du client est rapportee comme telle, et ils distinguent un serveur a jour d'un ancien
# binaire encore en memoire — ce que "GET /health" ne saura jamais dire.
function Base64Url($texte) {
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($texte)).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}
Check "curseur illisible -> 400" {
    (Status GET '/feed/cursor?cursor=pas-du-base64!!' @{} $null) -eq 400
}
Check "base64 valide sans separateur -> 400" {
    # Le cas sournois : le decodage base64 reussit et c'est le decoupage qui echouait, sur une
    # exception qu'aucun handler large n'aurait rattrapee.
    (Status GET ('/feed/cursor?cursor=' + (Base64Url 'sans-separateur')) @{} $null) -eq 400
}
Check "un curseur valide pagine toujours" {
    $page = Invoke-RestMethod "$base/feed/cursor?limit=2"
    $suite = Invoke-RestMethod "$base/feed/cursor?limit=2&cursor=$($page.nextCursor)"
    ($page.items.Count -eq 2) -and ($suite.items[0].id -ne $page.items[0].id)
}

Write-Host ""
Write-Host "== Le WebSocket de signalisation est publie ==" -ForegroundColor Cyan
Check "/ws existe (un GET simple n'est pas un 404)" {
    # Un endpoint WebSocket refuse un GET sans en-tetes d'upgrade, mais il repond.
    # Un 404 voudrait dire que WebSocketConfig n'a pas ete charge du tout.
    (Status GET '/ws' @{} $null) -ne 404
}

Write-Host ""
if ($failures -eq 0) {
    Write-Host "TOUT EST VERT - la bascule tient." -ForegroundColor Green
    exit 0
} else {
    Write-Host "$failures verification(s) en echec." -ForegroundColor Red
    exit 1
}
