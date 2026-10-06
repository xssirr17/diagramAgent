@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "ROOT_DIR=%SCRIPT_DIR%.."

if exist "%ROOT_DIR%\.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ROOT_DIR%\.env") do (
        set "%%A=%%B"
    )
)

for /f "delims=" %%I in ('dir /b /s "%ROOT_DIR%\build\libs\diagram-agent-*.jar" 2^>nul ^| findstr /v "plain"') do (
    set "JAR=%%I"
    goto :foundJar
)

echo diagram-agent.jar not found in build/libs. Building with gradlew.bat bootJar... 1>&2
pushd "%ROOT_DIR%"
call gradlew.bat bootJar -q
popd

for /f "delims=" %%I in ('dir /b /s "%ROOT_DIR%\build\libs\diagram-agent-*.jar" 2^>nul ^| findstr /v "plain"') do (
    set "JAR=%%I"
    goto :foundJar
)

echo Error: Failed to find or build diagram-agent.jar 1>&2
exit /b 1

:foundJar
java -Dspring.profiles.active=cli,gemini-api -Dspring.main.web-application-type=none -jar "%JAR%" %*
exit /b %ERRORLEVEL%
