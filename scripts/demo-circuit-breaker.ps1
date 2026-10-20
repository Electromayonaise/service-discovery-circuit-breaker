# Llama repetidamente a un endpoint de fx-catalog-service y muestra, por cada llamada,
# la latencia, la respuesta y el estado del Circuit Breaker "courseService" (via Actuator).
#
# Uso:  .\scripts\demo-circuit-breaker.ps1 [-Path /catalog] [-Calls 10] [-DelayMs 500]
param(
    [string]$Path = "/catalog",
    [int]$Calls = 10,
    [int]$DelayMs = 500,
    [string]$BaseUrl = "http://localhost:8002"
)

function Get-CircuitState {
    try {
        $cb = Invoke-RestMethod "$BaseUrl/actuator/circuitbreakers"
        $s = $cb.circuitBreakers.courseService
        return "$($s.state) (fallidas=$($s.failedCalls)/$($s.bufferedCalls), rechazadas=$($s.notPermittedCalls))"
    } catch {
        return "desconocido"
    }
}

for ($i = 1; $i -le $Calls; $i++) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $body = Invoke-RestMethod "$BaseUrl$Path"
    } catch {
        $body = "ERROR: $($_.Exception.Message)"
    }
    $watch.Stop()
    if ($body.Length -gt 90) { $body = $body.Substring(0, 90) + "..." }
    $color = if ($body -like "Fallback*" -or $body -like "*(Fallback*") { "Yellow" } else { "Green" }
    Write-Host ("#{0,-3} {1,6} ms  [{2}]" -f $i, $watch.ElapsedMilliseconds, (Get-CircuitState)) -NoNewline
    Write-Host "  $body" -ForegroundColor $color
    Start-Sleep -Milliseconds $DelayMs
}
