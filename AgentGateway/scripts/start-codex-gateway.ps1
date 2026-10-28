param(
    [string]$CodexCommand,
    [string]$Model = 'gpt-6.1-sol',
    [string]$ModelProvider = 'magpie',
    [int]$Port = 8080,
    [string]$JavaCommand = 'java'
)
$ErrorActionPreference = 'Stop'
$gatewayRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $gatewayRoot 'target\AgentGateway-0.0.1-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $jar)) {
    throw 'Build the Gateway jar with Maven package before running this script.'
}
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) {
    throw "Port $Port is already in use. Keep the existing service or choose another port."
}
if (!$CodexCommand) {
    $installed = Get-Command codex.exe -ErrorAction SilentlyContinue
    if ($installed) { $CodexCommand = $installed.Source }
    else { $CodexCommand = Join-Path $env:LOCALAPPDATA 'Programs\OpenAI\Codex\bin\codex.exe' }
}
if (!(Test-Path -LiteralPath $CodexCommand -PathType Leaf)) {
    throw 'Codex executable was not found. Pass -CodexCommand with its absolute path.'
}
$values = @{
    AGENT_CODEX_COMMAND = $CodexCommand
    AGENT_CODEX_MODEL = $Model
    AGENT_CODEX_MODEL_PROVIDER = $ModelProvider
    AGENT_CODEX_MCP_URL = "http://127.0.0.1:$Port/mcp"
}
$previous = @{}
try {
    foreach ($key in $values.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
    }
    Write-Host "Starting Gateway on port $Port; Codex provider=$ModelProvider model=$Model"
    & $JavaCommand -jar $jar "--server.port=$Port"
    $result = $LASTEXITCODE
} finally {
    foreach ($key in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
    }
}
exit $result
