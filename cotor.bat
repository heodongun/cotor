@echo off
REM Cotor CLI wrapper script for Windows

REM Get the directory where this script is located
set SCRIPT_DIR=%~dp0

REM Path to the JAR file
set JAR_PATH=%SCRIPT_DIR%build\libs\cotor-1.0.0.jar

REM Check if JAR exists
if not exist "%JAR_PATH%" (
    echo Error: cotor-1.0.0.jar not found at %JAR_PATH%
    echo Please run: gradlew.bat shadowJar
    exit /b 1
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
