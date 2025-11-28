@echo off
REM Cotor CLI wrapper script for Windows

REM Get the directory where this script is located
set SCRIPT_DIR=%~dp0
for %%I in ("%SCRIPT_DIR%..") do set PROJECT_ROOT=%%~fI

REM Resolve version (default to 1.0.0)
set APP_VERSION=1.0.0
for /f "tokens=2 delims==" %%v in ('findstr /B "version=" "%PROJECT_ROOT%\gradle.properties" 2^>NUL') do set APP_VERSION=%%v

REM Path to the shaded JAR file
set JAR_PATH=%PROJECT_ROOT%\build\libs\cotor-%APP_VERSION%-all.jar

REM Check if JAR exists
if not exist "%JAR_PATH%" (
    echo Building cotor CLI with gradlew.bat shadowJar...
    pushd "%PROJECT_ROOT%"
    if exist gradlew.bat (
        call gradlew.bat shadowJar
    ) else (
        echo Error: gradlew.bat not found in %PROJECT_ROOT%
        popd
        exit /b 1
    )
    popd
)

REM Check if Java is installed
java -version >nul 2>&1
if errorlevel 1 (
    echo Error: Java is not installed or not in PATH
    echo Please install JDK 17 or higher
    exit /b 1
)

REM Run the JAR with all arguments passed to this script
java -jar "%JAR_PATH%" %*
