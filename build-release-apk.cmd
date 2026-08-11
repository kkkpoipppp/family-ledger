@echo off
setlocal
cd /d "%~dp0"

set "GRADLE_USER_HOME=%CD%\.tooling\gradle-home"
set "LOCAL_GRADLE=%CD%\.tooling\gradle\gradle-9.3.1\bin\gradle.bat"

if not exist "keystore.properties" goto missing_signing
if exist "%LOCAL_GRADLE%" (
    set "GRADLE_COMMAND=%LOCAL_GRADLE%"
) else (
    set "GRADLE_COMMAND=%CD%\gradlew.bat"
)

if /I "%~1"=="--check" goto check_only

echo Building and testing signed Family Ledger release...
call "%GRADLE_COMMAND%" testDebugUnitTest :app:assembleRelease --no-daemon --no-configuration-cache --no-parallel
if errorlevel 1 goto failed

if not exist "dist" mkdir "dist"
copy /Y "app\build\outputs\apk\release\app-release.apk" "dist\family-ledger-cloud-v0.4.0-release.apk" >nul

echo.
echo Build succeeded: dist\family-ledger-cloud-v0.4.0-release.apk
certutil -hashfile "dist\family-ledger-cloud-v0.4.0-release.apk" SHA256
echo.
pause
exit /b 0

:missing_signing
echo Missing keystore.properties. Follow docs\RELEASE_SIGNING.md first.
pause
exit /b 1

:failed
echo.
echo Build failed. Please keep the error output above.
echo.
pause
exit /b 1

:check_only
echo Batch script parsing: OK
echo Gradle command: "%GRADLE_COMMAND%"
exit /b 0
