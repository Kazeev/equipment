param([switch]$Test)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($mavenCommand) {
    $mavenPath = $mavenCommand.Source
} else {
    $mavenPath = Join-Path $PSScriptRoot '.tools/apache-maven-3.9.11/bin/mvn.cmd'
    if (!(Test-Path -LiteralPath $mavenPath)) {
        New-Item -ItemType Directory -Force .tools | Out-Null
        Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip' -OutFile .tools/maven.zip
        Expand-Archive -Path .tools/maven.zip -DestinationPath .tools -Force
    }
}
$goal = if ($Test) { 'test' } else { 'spring-boot:run' }
& $mavenPath '-Dmaven.repo.local=.tools/repository' $goal
exit $LASTEXITCODE
