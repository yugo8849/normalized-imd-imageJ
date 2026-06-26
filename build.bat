@echo off
echo ================================
echo Normalized IMD Plugin Build Script
echo ================================
echo.

REM Check if Maven is installed
where mvn >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Maven is not installed or not in PATH
    echo.
    echo Please install Maven from: https://maven.apache.org/download.cgi
    echo And add it to your PATH environment variable
    pause
    exit /b 1
)

REM Check if Java is installed
where java >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    echo ERROR: Java is not installed or not in PATH
    echo.
    echo Please install JDK 8 or later from: https://adoptium.net/
    pause
    exit /b 1
)

echo Building plugin...
echo.
call mvn clean package

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ================================
    echo BUILD SUCCESSFUL!
    echo ================================
    echo.
    echo JAR file created: target\Normalized_IMD-1.1.0.jar
    echo.
    echo Next steps:
    echo 1. Copy the JAR to Fiji.app\plugins\
    echo    Also ensure Subtract Background Plus is installed in plugins\.
    echo 2. Restart Fiji
    echo 3. Access via: Plugins ^> FRET ^> Normalized IMD ^(dR/R0, dF/F0^)
    echo.
) else (
    echo.
    echo ================================
    echo BUILD FAILED
    echo ================================
    echo Check error messages above
)

pause
